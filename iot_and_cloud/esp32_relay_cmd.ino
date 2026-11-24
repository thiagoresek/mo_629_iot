/* Desenvolvido com auxílio de IA
  ============================================================
  ESP32 #2 - BLE Relay (Observer + Broadcaster)
  Retransmite ALERTAS em Service Data (beacon -> gateway/celulares)
  e COMANDOS (gateway -> beacon)
  ============================================================

  Sentido 1 - ALERTA (muda em relação à versão anterior):
      beacon --SD 0x1234--> relay --SD 0x1234 + marcador 0x1235--> gateway / celulares
    O beacon original anuncia o VALOR em Service Data (UUID 0x1234) e um nome
    fixo. O relay copia os dois e retransmite por 5 s acrescentando o
    marcador 0x1235 (lista de UUIDs de serviço) para que ninguém reprocesse a
    retransmissão. "Beacon original" = Service Data 0x1234 SEM marcador 0x1235.

    Pacote retransmitido:
        flags 3 + marcador 0x1235 (4) + Service Data (4 + V) + nome (2 + N) <= 31
        => V + N <= 18 (TOTAL_MAX_BYTES). Se o original exceder, o relay corta
           primeiro o nome (o valor tem prioridade).

    Qualquer dispositivo que anuncie Service Data 0x1234 (inclusive o app
    "BLE Advertiser" usado para testes no celular) é tratado como beacon.

  Sentido 2 - COMANDO (INALTERADO):
      gateway --0x1236--> relay --0x1236--> beacon
    nome = [2 hex SEQ][5 hex TAG][valor até 18 bytes]. O relay confere a TAG
    com a mesma CHAVE_COMANDO, só repassa SEQ mais nova (diferença 1..127) e
    retransmite o comando, sem alterá-lo, por 30 s.

  Avisos:
    - Enquanto repassa um comando (30 s) o relay não escaneia nem retransmite
      alertas. Comandos são raros.
    - Chave fixa no código e TAG de 20 bits: proteção de protótipo.
      CHAVE_COMANDO precisa ser IGUAL nos 3 sketches.

  Convenção de UUIDs (16 bits, valores de protótipo, não atribuídos pelo SIG):
    0x1234 = Service Data do alerta     0x1235 = marcador de retransmitido
    0x1236 = comando de atualização do valor

  Pontos a testar na prática (nRF Connect):
    - O pacote retransmitido deve mostrar Service Data 0x1234 = valor, nome e
      lista de serviços 0x1235.
    - O pacote de comando não tem flags (opcional em pacotes de broadcast).
*/

#include <ctype.h>
#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEScan.h>
#include <BLEAdvertisedDevice.h>
#include <BLEAdvertising.h>
#include "mbedtls/md.h"

#define VALOR_FALLBACK  "0000"
#define NOME_FALLBACK   ""
#define UUID_ALERTA     "1234"      // Service Data do alerta
#define UUID_RELAY      "1235"      // marcador "foi retransmitido"
#define UUID_COMANDO    "1236"
#define TOTAL_MAX_BYTES 18          // valor + nome
#define CHAVE_COMANDO   "carrapato-unicamp-2026"          // IGUAL nos 3 sketches
#define MAX_CANDIDATOS  6

// --- Parâmetros de tempo ---
const uint32_t DURACAO_SCAN_S         = 4;
const uint32_t DURACAO_ADVERTISING_MS = 5000;    // retransmissão de alerta
const uint32_t DURACAO_COMANDO_MS     = 30000;   // repasse de comando

BLEScan *pBLEScan;
BLEAdvertising *pAdvertising;

// Alerta
volatile bool beaconDetectado = false;
String valorOrigem = VALOR_FALLBACK;
String nomeOrigem  = NOME_FALLBACK;

// Comando
String       candidatos[MAX_CANDIDATOS];
volatile int nCandidatos = 0;
bool    temUltimaSeq = false;
uint8_t ultimaSeqEnc = 0;

// ---------- utilidades ----------

String limitarBytes(const String &s, size_t max) {
  if (s.length() <= max) return s;
  size_t n = max;
  while (n > 0 && (((uint8_t)s[n]) & 0xC0) == 0x80) n--;
  return s.substring(0, n);
}

// Procura Service Data com o UUID dado; devolve o conteúdo em "valor"
bool lerServiceData(BLEAdvertisedDevice &d, const char *uuid, String &valor) {
  if (!d.haveServiceData()) return false;
  int n = d.getServiceDataUUIDCount();
  int m = d.getServiceDataCount();
  if (m < n) n = m;
  BLEUUID alvo(uuid);
  for (int i = 0; i < n; i++) {
    if (d.getServiceDataUUID(i).equals(alvo)) {
      valor = d.getServiceData(i);
      return true;
    }
  }
  return false;
}

String calcularTag(const String &seqHex, const String &msg) {
  String dados = seqHex + msg;
  unsigned char h[32];
  mbedtls_md_context_t ctx;
  mbedtls_md_init(&ctx);
  mbedtls_md_setup(&ctx, mbedtls_md_info_from_type(MBEDTLS_MD_SHA256), 1);
  mbedtls_md_hmac_starts(&ctx, (const unsigned char *)CHAVE_COMANDO, strlen(CHAVE_COMANDO));
  mbedtls_md_hmac_update(&ctx, (const unsigned char *)dados.c_str(), dados.length());
  mbedtls_md_hmac_finish(&ctx, h);
  mbedtls_md_free(&ctx);
  char hex[8];
  snprintf(hex, sizeof(hex), "%02x%02x%02x", h[0], h[1], h[2]);
  hex[5] = 0;
  return String(hex);
}

bool validarComando(const String &nome, uint8_t &seq) {
  if (nome.length() < 7 || nome.length() > 25) return false;
  if (!isxdigit(nome[0]) || !isxdigit(nome[1])) return false;
  String seqHex = nome.substring(0, 2);
  String tag    = nome.substring(2, 7);
  String msg    = nome.substring(7);
  seq = (uint8_t)strtol(seqHex.c_str(), NULL, 16);
  return calcularTag(seqHex, msg) == tag;
}

// ---------- scan ----------

class MeuCallbackDeScan : public BLEAdvertisedDeviceCallbacks {
  void onResult(BLEAdvertisedDevice advertisedDevice) override {

    // --- Comando (0x1236) vindo do gateway ou de outro relay ---
    if (advertisedDevice.haveServiceUUID() &&
        advertisedDevice.isAdvertisingService(BLEUUID(UUID_COMANDO))) {
      if (!advertisedDevice.haveName()) return;
      String nome = String(advertisedDevice.getName().c_str());
      if (nome.length() < 7 || nome.length() > 25) return;
      if (!isxdigit(nome[0]) || !isxdigit(nome[1])) return;
      if (temUltimaSeq) {                                  // pré-filtro barato
        uint8_t seq  = (uint8_t)strtol(nome.substring(0, 2).c_str(), NULL, 16);
        uint8_t diff = (uint8_t)(seq - ultimaSeqEnc);
        if (diff == 0 || diff > 127) return;
      }
      for (int i = 0; i < nCandidatos; i++) if (candidatos[i] == nome) return;
      if (nCandidatos < MAX_CANDIDATOS) candidatos[nCandidatos++] = nome;
      return;
    }

    // --- Alerta: beacon original = Service Data 0x1234 sem marcador 0x1235 ---
    String valor;
    if (!lerServiceData(advertisedDevice, UUID_ALERTA, valor)) return;
    bool jaRetransmitido = advertisedDevice.haveServiceUUID() &&
                           advertisedDevice.isAdvertisingService(BLEUUID(UUID_RELAY));
    if (jaRetransmitido) return;
    if (valor.length() == 0) return;

    valorOrigem = limitarBytes(valor, TOTAL_MAX_BYTES);
    String nome = advertisedDevice.haveName()
                    ? String(advertisedDevice.getName().c_str())
                    : String("");
    nomeOrigem = limitarBytes(nome, TOTAL_MAX_BYTES - valorOrigem.length());  // valor tem prioridade

    Serial.print("Beacon original detectado! valor=\"");
    Serial.print(valorOrigem);
    Serial.print("\" nome=\"");
    Serial.print(nomeOrigem);
    Serial.print("\" RSSI: ");
    Serial.println(advertisedDevice.getRSSI());

    beaconDetectado = true;
  }
};

// ---------- retransmissão ----------

void configurarAdvertisingAlerta(const String &valor, const String &nome) {
  BLEAdvertisementData advData;
  advData.setFlags(0x06);
  advData.setCompleteServices(BLEUUID(UUID_RELAY));           // "foi retransmitido"
  advData.setServiceData(BLEUUID(UUID_ALERTA), valor);        // o alerta (mesmo valor)
  if (nome.length() > 0) advData.setName(nome.c_str());       // mesmo nome do beacon

  pAdvertising->setAdvertisementData(advData);
  pAdvertising->setScanResponse(false);
  pAdvertising->setMinPreferred(0x06);
}

void configurarAdvertisingComando(const String &nome) {
  BLEAdvertisementData advData;
  advData.setName(nome.c_str());
  advData.setCompleteServices(BLEUUID(UUID_COMANDO));  // sem flags: 31 bytes no total

  pAdvertising->setAdvertisementData(advData);
  pAdvertising->setScanResponse(false);
}

void repassarComando() {
  // Escolhe o comando válido com SEQ mais nova
  bool achou = false;
  uint8_t melhorSeq = 0, melhorDiff = 0;
  String melhorNome;
  for (int i = 0; i < nCandidatos; i++) {
    uint8_t seq;
    if (!validarComando(candidatos[i], seq)) {
      Serial.println("Comando com assinatura invalida ignorado.");
      continue;
    }
    uint8_t diff = temUltimaSeq ? (uint8_t)(seq - ultimaSeqEnc) : 1;
    if (temUltimaSeq && (diff == 0 || diff > 127)) continue;
    if (!achou || diff > melhorDiff) {
      achou = true; melhorSeq = seq; melhorDiff = diff; melhorNome = candidatos[i];
    }
  }
  if (!achou) return;

  Serial.print("Comando valido (seq=");
  Serial.print(melhorSeq);
  Serial.println("). Repassando por 30 s...");

  configurarAdvertisingComando(melhorNome);
  BLEDevice::startAdvertising();
  delay(DURACAO_COMANDO_MS);
  BLEDevice::stopAdvertising();

  temUltimaSeq = true;
  ultimaSeqEnc = melhorSeq;
  Serial.println("Repasse de comando finalizado.");
}

void setup() {
  Serial.begin(115200);
  delay(1000);
  Serial.println("Iniciando ESP32 Relay (alerta em Service Data + comando)...");

  BLEDevice::init("ESP32-Relay");
  pAdvertising = BLEDevice::getAdvertising();

  pBLEScan = BLEDevice::getScan();
  pBLEScan->setAdvertisedDeviceCallbacks(new MeuCallbackDeScan());
  pBLEScan->setActiveScan(false);
  pBLEScan->setInterval(100);
  pBLEScan->setWindow(99);

  Serial.println("Configuracao concluida. Iniciando ciclo de escaneamento...");
}

void loop() {
  Serial.println("Escaneando...");
  beaconDetectado = false;
  nCandidatos = 0;
  pBLEScan->start(DURACAO_SCAN_S, false);
  pBLEScan->clearResults();

  // 1) Alerta tem prioridade (o burst do beacon dura só 5 s)
  if (beaconDetectado) {
    configurarAdvertisingAlerta(valorOrigem, nomeOrigem);

    Serial.print("Retransmitindo alerta: valor=");
    Serial.print(valorOrigem);
    Serial.print(" nome=");
    Serial.println(nomeOrigem);

    BLEDevice::startAdvertising();
    delay(DURACAO_ADVERTISING_MS);
    BLEDevice::stopAdvertising();
  } else {
    Serial.println("Beacon original nao detectado neste ciclo.");
  }

  // 2) Comando pendente de repasse
  if (nCandidatos > 0) {
    repassarComando();
  }
}
