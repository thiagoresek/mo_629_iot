/* Desenvolvido com auxílo de IA 
  ============================================================
  ESP32 #1 - Beacon "Alerta": VALOR em Service Data + NOME, ambos
  atualizáveis por BLE (comando assinado do gateway, direto ou via relay)
  ============================================================

  Pacote anunciado (legacy advertising, 31 bytes no máximo):
      AD flags                     3 bytes
      AD Service Data 0x1234       4 + VALOR   (2 de cabeçalho AD + 2 de UUID)
      AD nome completo             2 + NOME
    Padrão: valor "0001" e nome "IC" => 3 + (4+4) + (2+2) = 15 bytes.
    O app de celular do grupo lê o Service Data 0x1234 e converte o código
    em mensagem. O nome é só um rótulo para scanners genéricos (nRF Connect).

  Limites (fixos, iguais no gateway, para o beacon nunca cortar o que o
  gateway mandou e a confirmação bater):
      VALOR_MAX_BYTES = 12      NOME_MAX_BYTES = 5      (12 + 5 = 17)
    Orçamento: o relay retransmite com o marcador 0x1235 (4 bytes):
      flags 3 + marcador 4 + Service Data (4+12) + nome (2+5) = 30 <= 31.
    O comando carrega "valor|nome" (12 + 1 + 5 = 18 bytes), por isso o
    caractere '|' é proibido dentro do valor e do nome.

  Atualização remota (opção A):
    1) anuncia o alerta 5 s a cada 30 s;
    2) depois escuta 10 s por um COMANDO (UUID 0x1236) do gateway/relay;
    3) comando válido => grava valor e nome (Preferences) e os anuncia no
       burst seguinte.

  Pacote de comando (31 bytes, sem flags):
      AD UUID16 completo 0x1236                              (4 bytes)
      AD nome completo (25 bytes): [2 hex SEQ][5 hex TAG][valor|nome]
    TAG = 5 primeiros hex de HMAC-SHA256(CHAVE_COMANDO, SEQ_hex + "valor|nome").
    Aceita se a TAG confere e SEQ é mais nova (diferença circular 1..127).
    Regras do conteúdo "valor|nome":
      - valor vazio  => volta para VALOR_PADRAO;
      - sem '|'      => só o valor muda, o nome fica como está;
      - nome vazio (termina em '|') => volta para NOME_PADRAO.

  Limites e avisos:
    - Sem ACK no enlace. O gateway repete o comando e considera entregue quando
      ouve o alerta com Service Data = valor alvo E nome = nome alvo.
    - TAG de 20 bits e chave fixa: proteção de protótipo. CHAVE_COMANDO precisa
      ser IGUAL nos 3 sketches.
    - Reiniciar a numeração do gateway (memória apagada) com SEQ maior guardada
      aqui faz o beacon rejeitar comandos: use RESETAR_MEMORIA 1 uma vez.
    - Valor e nome ficam nas chaves "valor" e "nome" da memória.
    - Escutar consome mais energia que só anunciar.
    - O app/scanner deve filtrar por SERVICE DATA UUID 0x1234 (este pacote
      não tem 0x1234 na lista de UUIDs de serviço).
    - A zona deixou de viajar no pacote (antes ia dentro do nome).

  Bibliotecas: já inclusas no core ESP32 do Arduino (BLE, Preferences, mbedtls).
  Uso: Monitor Serial a 115200 baud.
*/

#include <ctype.h>
#include <Preferences.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLEAdvertising.h>
#include <BLEScan.h>
#include <BLEAdvertisedDevice.h>
#include "mbedtls/md.h"

// --- CONFIGURAÇÃO ---
#define VALOR_PADRAO    "0001"                       // Service Data (ASCII)
#define NOME_PADRAO     "IC"                         // nome BLE
#define CHAVE_COMANDO   "carrapato-unicamp-2026"          // IGUAL nos 3 sketches
#define RESETAR_MEMORIA 0                            // 1 = apaga a memória na partida (ver aviso acima)

#define UUID_ALERTA     "1234"   // Service Data do alerta deste sistema
#define UUID_COMANDO    "1236"   // comando de atualização
#define VALOR_MAX_BYTES 12       // IGUAL no gateway
#define NOME_MAX_BYTES  5        // IGUAL no gateway
#define SEPARADOR       '|'      // separa valor e nome no comando
#define MAX_CANDIDATOS  6

static_assert(sizeof(VALOR_PADRAO) - 1 >= 1 && sizeof(VALOR_PADRAO) - 1 <= VALOR_MAX_BYTES, "VALOR_PADRAO fora de 1..VALOR_MAX_BYTES");
static_assert(sizeof(NOME_PADRAO) - 1 >= 1 && sizeof(NOME_PADRAO) - 1 <= NOME_MAX_BYTES, "NOME_PADRAO fora de 1..NOME_MAX_BYTES");

// Ciclo de 30 s: anuncia 5 s, escuta 10 s, descansa o resto
const unsigned long INTERVALO_CICLO_MS     = 30000;
const unsigned long DURACAO_ADVERTISING_MS = 5000;
const uint32_t      DURACAO_ESCUTA_S       = 10;

Preferences     prefs;
BLEAdvertising *pAdvertising;
BLEScan        *pBLEScan;

String  valorAtual = VALOR_PADRAO;
String  nomeAtual  = NOME_PADRAO;
uint8_t ultimaSeq  = 0;

String       candidatos[MAX_CANDIDATOS];
volatile int nCandidatos = 0;

// ---------- utilidades ----------

String limitarBytes(const String &s, size_t max) {
  if (s.length() <= max) return s;
  size_t n = max;
  while (n > 0 && (((uint8_t)s[n]) & 0xC0) == 0x80) n--;   // não parte UTF-8
  return s.substring(0, n);
}

// Só ASCII imprimível (0x20-0x7E), sem aspas, barra invertida nem '|'
String prepararTexto(const String &s, size_t max) {
  String r;
  for (size_t i = 0; i < s.length(); i++) {
    char c = s[i];
    if (c == '"' || c == '\\' || c == SEPARADOR || (uint8_t)c < 0x20 || (uint8_t)c > 0x7E) continue;
    r += c;
  }
  return limitarBytes(r, max);
}
String prepararValor(const String &s) { return prepararTexto(s, VALOR_MAX_BYTES); }
String prepararNome(const String &s)  { return prepararTexto(s, NOME_MAX_BYTES); }

// 5 primeiros hex de HMAC-SHA256(CHAVE_COMANDO, seqHex + valor)
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

// Confere estrutura e assinatura; devolve SEQ e valor
bool validarComando(const String &nome, uint8_t &seq, String &msg) {
  if (nome.length() < 7 || nome.length() > 25) return false;
  if (!isxdigit(nome[0]) || !isxdigit(nome[1])) return false;
  String seqHex = nome.substring(0, 2);
  String tag    = nome.substring(2, 7);
  msg           = nome.substring(7);
  seq           = (uint8_t)strtol(seqHex.c_str(), NULL, 16);
  return calcularTag(seqHex, msg) == tag;
}

// ---------- valor ativo ----------

// conteudo = "valor|nome" (ver regras no cabeçalho)
void aplicarComando(const String &conteudo) {
  int sep = conteudo.indexOf(SEPARADOR);
  String valorRec = (sep < 0) ? conteudo : conteudo.substring(0, sep);

  String novoValor = prepararValor(valorRec);
  if (novoValor.length() == 0) novoValor = VALOR_PADRAO;

  String novoNome = nomeAtual;                       // sem '|': nome inalterado
  if (sep >= 0) {
    novoNome = prepararNome(conteudo.substring(sep + 1));
    if (novoNome.length() == 0) novoNome = NOME_PADRAO;
  }

  if (novoValor != valorAtual) {
    valorAtual = novoValor;
    prefs.putString("valor", valorAtual);
  }
  if (novoNome != nomeAtual) {
    nomeAtual = novoNome;
    prefs.putString("nome", nomeAtual);
  }
  Serial.print("Aplicado (vale no proximo burst): valor=");
  Serial.print(valorAtual);
  Serial.print(" nome=");
  Serial.println(nomeAtual);
}

// ---------- escuta de comandos ----------

class CallbackComando : public BLEAdvertisedDeviceCallbacks {
  void onResult(BLEAdvertisedDevice advertisedDevice) override {
    if (!advertisedDevice.haveServiceUUID()) return;
    if (!advertisedDevice.isAdvertisingService(BLEUUID(UUID_COMANDO))) return;
    if (!advertisedDevice.haveName()) return;

    String nome = String(advertisedDevice.getName().c_str());
    // Pré-filtro barato (a assinatura é conferida depois do scan)
    if (nome.length() < 7 || nome.length() > 25) return;
    if (!isxdigit(nome[0]) || !isxdigit(nome[1])) return;
    uint8_t seq  = (uint8_t)strtol(nome.substring(0, 2).c_str(), NULL, 16);
    uint8_t diff = (uint8_t)(seq - ultimaSeq);
    if (diff == 0 || diff > 127) return;                 // repetido ou antigo
    for (int i = 0; i < nCandidatos; i++) if (candidatos[i] == nome) return;
    if (nCandidatos < MAX_CANDIDATOS) candidatos[nCandidatos++] = nome;
  }
};

void escutarComando() {
  nCandidatos = 0;
  Serial.println("Escutando comandos...");
  pBLEScan->start(DURACAO_ESCUTA_S, false);
  pBLEScan->clearResults();

  bool achou = false;
  uint8_t melhorSeq = 0, melhorDiff = 0;
  String melhorValor;
  for (int i = 0; i < nCandidatos; i++) {
    uint8_t seq; String msg;
    if (!validarComando(candidatos[i], seq, msg)) {
      Serial.println("Comando com assinatura invalida ignorado.");
      continue;
    }
    uint8_t diff = (uint8_t)(seq - ultimaSeq);
    if (!achou || diff > melhorDiff) {
      achou = true; melhorSeq = seq; melhorDiff = diff; melhorValor = msg;
    }
  }

  if (achou) {
    Serial.print("Comando valido! seq=");
    Serial.println(melhorSeq);
    ultimaSeq = melhorSeq;
    prefs.putUChar("seq", ultimaSeq);
    aplicarComando(melhorValor);
  }
}

// ---------- BLE (alerta) ----------

void configurarAdvertising() {
  BLEAdvertisementData advData;
  advData.setFlags(0x06);
  advData.setServiceData(BLEUUID(UUID_ALERTA), valorAtual);   // AD 0x16: UUID 0x1234 + valor
  if (nomeAtual.length() > 0) advData.setName(nomeAtual.c_str());   // AD nome

  pAdvertising->setAdvertisementData(advData);
  pAdvertising->setScanResponse(false);
  pAdvertising->setMinPreferred(0x06);
}

void setup() {
  Serial.begin(115200);
  delay(1000);
  Serial.println("Iniciando ESP32 Beacon (Service Data " UUID_ALERTA ", comando " UUID_COMANDO ")...");

  prefs.begin("alerta", false);
  if (RESETAR_MEMORIA) {
    prefs.clear();
    Serial.println("Memoria apagada (RESETAR_MEMORIA=1). Volte para 0 e grave de novo.");
  }
  valorAtual = prepararValor(prefs.getString("valor", VALOR_PADRAO));
  if (valorAtual.length() == 0) valorAtual = VALOR_PADRAO;
  nomeAtual = prepararNome(prefs.getString("nome", NOME_PADRAO));
  if (nomeAtual.length() == 0) nomeAtual = NOME_PADRAO;
  ultimaSeq = prefs.getUChar("seq", 0);
  Serial.print("Valor inicial: ");
  Serial.print(valorAtual);
  Serial.print(" | nome: ");
  Serial.print(nomeAtual);
  Serial.print(" | ultima seq: ");
  Serial.println(ultimaSeq);

  BLEDevice::init("ESP32-Beacon");
  pAdvertising = BLEDevice::getAdvertising();

  pBLEScan = BLEDevice::getScan();
  pBLEScan->setAdvertisedDeviceCallbacks(new CallbackComando());
  pBLEScan->setActiveScan(false);
  pBLEScan->setInterval(100);
  pBLEScan->setWindow(99);

  Serial.println("Configuracao concluida.");
}

void loop() {
  configurarAdvertising();                       // usa o valor mais recente
  Serial.print("Advertising ATIVO. Service Data 0x" UUID_ALERTA " = ");
  Serial.print(valorAtual);
  Serial.print(" | nome = ");
  Serial.println(nomeAtual);
  BLEDevice::startAdvertising();
  delay(DURACAO_ADVERTISING_MS);
  BLEDevice::stopAdvertising();

  escutarComando();

  Serial.println("Aguardando proximo ciclo...");
  delay(INTERVALO_CICLO_MS - DURACAO_ADVERTISING_MS - DURACAO_ESCUTA_S * 1000UL);
}
