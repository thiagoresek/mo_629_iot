/* Desenvolvido com auxílio de IA
  ============================================================
  ESP32 #3 - Gateway (BLE Observer + Broadcaster <-> MQTT / ThingsBoard)
  Opção A: envia ao beacon, por BLE, o VALOR editado no ThingsBoard
  O alerta agora é lido em SERVICE DATA (UUID 0x1234) + nome
  VERSÃO COM DIAGNÓSTICO NA SERIAL (115200 baud)
  ============================================================

  SENTIDO SUBIDA (alerta):
    escaneia BLE, aceita pacotes com Service Data de UUID 0x1234 (do beacon ou
    retransmitidos pelo relay) e publica
      {"alerta":true,"zona":"desconhecida","codigo":"0001","mensagem":"IC"}
    codigo   = valor do Service Data (ASCII imprimível);
    mensagem = nome BLE do pacote; a zona só é extraída se o nome tiver
               "zona=X" (com o nome "IC" ela fica "desconhecida").

  SENTIDO DESCIDA: atualização do VALOR (Service Data) do beacon
    1. No ThingsBoard, no device DO GATEWAY: Attributes > Shared attributes >
       chaves  mensagemBeacon  (VALOR do Service Data, ASCII, até 12 bytes)
               nomeBeacon      (NOME BLE, ASCII, até 5 bytes; padrão "IC")
       Sem '|' nem aspas. Se só uma chave mudar, a outra mantém o valor atual.
       O gateway manda os dois no mesmo comando: conteúdo = "valor|nome".
    2. O gateway mantém o MQTT aberto, assina as mudanças desse atributo e
       também o pede ao conectar (assim não perde edições feitas offline).
    3. Valor novo => SEQ++ (guardada na memória) e o gateway anuncia por
       BLE o COMANDO por DURACAO_COMANDO_MS (30 s):
           AD UUID 16 bits 0x1236  +  AD nome = [2 hex SEQ][5 hex TAG][valor]
       TAG = 5 primeiros hex de HMAC-SHA256(CHAVE_COMANDO, SEQ_hex + valor).
    4. O relay (se o beacon estiver fora do alcance do gateway) repassa o
       comando; o beacon valida, grava e passa a anunciar o valor novo.
    5. CONFIRMAÇÃO: não há ACK no enlace de comando. O gateway considera
       entregue quando ouve (direto ou via relay) um alerta cujo Service Data
       é igual ao valor alvo. Enquanto não ouvir, reemite a cada
       REEMISSAO_MS (2 min), no máximo MAX_TENTATIVAS (5) vezes.

    Telemetria de acompanhamento (chave comandoStatus):
       "enviando" | "reenviando" | "confirmado" | "sem_confirmacao"
       (junto de comandoSeq, mensagemAlvo e nomeAlvo)

  DIAGNÓSTICO PELA SERIAL (Monitor Serial a 115200 baud)
    Prefixos das mensagens:
      [TB]     o que chegou do ThingsBoard: tópico, conteúdo bruto e, para
               cada chave, o valor bruto, o lido e o usado. Se mensagemBeacon
               chegar como NÚMERO (sem aspas), aparece "IGNORADO".
      [CMD]    alvo novo, comando sem mudança, emissões, espera da
               confirmação e o resultado (BEACON ATUALIZADO / NAO confirmou).
      [ALERTA] cada alerta ouvido: origem (beacon ou relay), RSSI, valor e
               nome; com comando pendente, diz o que ainda não confere.
      [SERIAL] eco do que foi digitado no Monitor Serial.

    COMANDOS PELO MONITOR SERIAL (qualquer terminação de linha):
      0001          envia o valor 0001 ao beacon (o nome fica como está)
      0001|AR       envia valor e nome
      nome=AR       envia só o nome (mantém o valor alvo atual)
      reenviar      reenvia o alvo atual com SEQ nova (teste de entrega)
      status  ou ?  mostra alvo, estado do comando e último alerta ouvido
      ajuda         lista os comandos
    Por isso "status", "ajuda" e "reenviar" não podem ser usados como valor.
    Aviso: o valor digitado na serial NÃO muda o ThingsBoard. Quando o MQTT
    reconectar (ou o gateway reiniciar), o gateway pede os atributos e, se
    forem diferentes, volta a enviar o valor do ThingsBoard ao beacon.

  Avisos:
    - Durante a emissão do comando (30 s) o gateway não escaneia alertas.
    - Valor vazio no atributo é ignorado (o beacon só volta ao padrão
      se receber comando vazio, que este gateway não envia).
    - A confirmação depende de o beacon ou o relay estarem ao alcance do
      gateway para o alerta de volta.
    - VALOR_MAX_BYTES (12), NOME_MAX_BYTES (5) e NOME_PADRAO ("IC") DEVEM ser
      iguais aos do beacon. Se o gateway aceitasse mais, o beacon cortaria o
      texto e a confirmação nunca bateria.
    - Chave fixa e TAG de 20 bits: proteção de protótipo. CHAVE_COMANDO
      precisa ser IGUAL nos 3 sketches.
    - Neste desenho o beacon não tem device próprio no ThingsBoard.

  IMPORTANTE: BLE + WiFi + MQTT não cabem na partição padrão.
  Ferramentas > Partition Scheme > "Huge APP (3MB No OTA)".

  Cadeia do projeto:
      beacon <--BLE--> relay <--BLE--> gateway <--WiFi/MQTT--> ThingsBoard

  ANTES DE GRAVAR, PREENCHA: WIFI_SSID, WIFI_SENHA, THINGSBOARD_TOKEN
  (device do gateway) e confirme MQTT_HOST ("Check connectivity").

  Bibliotecas: PubSubClient (Gerenciador de Bibliotecas). BLE, WiFi,
  Preferences e mbedtls vêm com o core ESP32.
*/

#include <WiFi.h>
#include <PubSubClient.h>
#include <Preferences.h>
#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEScan.h>
#include <BLEAdvertisedDevice.h>
#include <BLEAdvertising.h>
#include "mbedtls/md.h"

// ---------------- CONFIGURAÇÃO: PREENCHA AQUI ----------------
const char* WIFI_SSID         = "SSID";
const char* WIFI_SENHA        = "SENHA";
const char* THINGSBOARD_TOKEN = "soCyjWgcPSK39rfz1234";
#define CHAVE_COMANDO "carrapato-unicamp-2026"            // IGUAL nos 3 sketches
// ----------------------------------------------------------------

// ThingsBoard Cloud (confirme o host da sua conta)
const char* MQTT_HOST  = "mqtt.thingsboard.cloud";
const int   MQTT_PORT  = 1883;
const char* MQTT_TOPIC = "v2/t";                                        // telemetria
const char* TOPICO_ATRIBUTOS = "v1/devices/me/attributes";              // updates
const char* TOPICO_RESPOSTA  = "v1/devices/me/attributes/response/+";   // resposta ao pedido
const char* TOPICO_PEDIDO    = "v1/devices/me/attributes/request/1";    // pedido do valor atual
const char* CHAVE_ATRIBUTO   = "mensagemBeacon";   // valor (Service Data)
const char* CHAVE_NOME       = "nomeBeacon";       // nome BLE

// BLE
#define UUID_ALERTA     "1234"   // Service Data do alerta
#define UUID_RELAY      "1235"   // marcador "retransmitido pelo relay" (só para o diagnóstico)
#define UUID_COMANDO    "1236"
#define VALOR_MAX_BYTES 12       // IGUAL ao do beacon
#define NOME_MAX_BYTES  5        // IGUAL ao do beacon
#define NOME_PADRAO     "IC"     // IGUAL ao do beacon
#define SEPARADOR       '|'      // separa valor e nome no comando

// LED de sinalização (pisca 10 vezes ao conectar no Wi-Fi)
// Nas placas ESP32 DevKit o LED azul costuma ficar no GPIO 2.
// Se a sua placa não tiver LED nesse pino, ligue um LED + resistor (~220 ohm)
// do pino ao GND, ou troque o número do pino.
#ifdef LED_BUILTIN
#define PINO_LED        LED_BUILTIN
#else
#define PINO_LED        2
#endif
#define LED_ATIVO_ALTO  1        // 0 se o LED da placa acender com LOW
#define PISCADAS_WIFI   10

const uint32_t      DURACAO_SCAN_S     = 4;
const unsigned long DURACAO_COMANDO_MS = 30000;   // tempo no ar de cada emissão
const unsigned long REEMISSAO_MS       = 120000;  // espera entre emissões sem confirmação
const int           MAX_TENTATIVAS     = 5;

WiFiClient   espClient;
PubSubClient mqttClient(espClient);
Preferences  prefs;
BLEScan     *pBLEScan;
BLEAdvertising *pAdvertising;

// Alerta (subida)
volatile bool beaconDetectado = false;
String zonaDetectada     = "desconhecida";
String mensagemDetectada = "";   // nome BLE
String codigoDetectado   = "";   // Service Data

// Comando (descida)
String   mensagemAlvo = "";            // valor (Service Data) alvo
String   nomeAlvo     = NOME_PADRAO;   // nome alvo
uint8_t  seqAtual     = 0;
bool     confirmada   = true;
volatile bool comandoPendente = false;
volatile bool confirmou       = false;
int      tentativas       = 0;
unsigned long proximaEmissaoMs = 0;

// Diagnóstico
String ultimoValorOuvido  = "";
String ultimoNomeOuvido   = "";
String ultimaOrigemOuvida = "";
unsigned long ultimoAlertaMs = 0;
String linhaSerial = "";
unsigned long ultimoCaractereMs = 0;

// ---------- utilidades ----------

String limitarBytes(const String &s, size_t max) {
  if (s.length() <= max) return s;
  size_t n = max;
  while (n > 0 && (((uint8_t)s[n]) & 0xC0) == 0x80) n--;
  return s.substring(0, n);
}

String limparTexto(const String &s) {            // só ASCII imprimível; protege o JSON
  String r;
  for (size_t i = 0; i < s.length(); i++) {
    char c = s[i];
    if (c == '"' || c == '\\' || c == SEPARADOR || (uint8_t)c < 0x20 || (uint8_t)c > 0x7E) continue;
    r += c;
  }
  return r;
}

String prepararMensagem(const String &s) {          // valor
  return limitarBytes(limparTexto(s), VALOR_MAX_BYTES);
}

String prepararNome(const String &s) {
  return limitarBytes(limparTexto(s), NOME_MAX_BYTES);
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

String extrairZona(const String &nomeCompleto) {
  int pos = nomeCompleto.indexOf("zona=");
  if (pos == -1) return "desconhecida";
  return nomeCompleto.substring(pos + 5);
}

String hex2(uint8_t v) {
  char b[4];
  snprintf(b, sizeof(b), "%02x", v);
  return String(b);
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

// Lê o valor (string) de uma chave em um JSON simples, nos dois formatos:
//   update:   {"mensagemBeacon":"..."}
//   resposta: {"shared":{"mensagemBeacon":"..."}}
bool extrairString(const String &json, const char *chave, String &saida) {
  String procurado = String("\"") + chave + "\"";
  int pos = json.indexOf(procurado);
  if (pos < 0) return false;
  pos = json.indexOf(':', pos);
  if (pos < 0) return false;
  pos++;
  int n = json.length();
  while (pos < n && json[pos] == ' ') pos++;
  if (pos >= n || json[pos] != '"') return false;
  pos++;
  saida = "";
  while (pos < n) {
    char c = json[pos];
    if (c == '\\') {
      pos += (pos + 1 < n && json[pos + 1] == 'u') ? 6 : 2;
      continue;
    }
    if (c == '"') return true;
    saida += c;
    pos++;
  }
  return false;
}

// ---------- diagnóstico ----------

// Trecho bruto do valor de uma chave no JSON (sem interpretar), para mostrar
// na serial exatamente o que o ThingsBoard mandou. "" se a chave não veio.
String valorBrutoJson(const String &json, const char *chave) {
  String procurado = String("\"") + chave + "\"";
  int pos = json.indexOf(procurado);
  if (pos < 0) return "";
  int n = json.length();
  pos += procurado.length();
  while (pos < n && json[pos] == ' ') pos++;
  if (pos >= n || json[pos] != ':') return "";
  pos++;
  while (pos < n && json[pos] == ' ') pos++;
  int fim = pos;
  if (fim < n && json[fim] == '"') {             // string: vai até a aspa de fechamento
    fim++;
    while (fim < n) {
      if (json[fim] == '\\') { fim += 2; continue; }
      if (json[fim] == '"') { fim++; break; }
      fim++;
    }
  } else {                                       // número, true/false, null...
    while (fim < n && json[fim] != ',' && json[fim] != '}') fim++;
  }
  if (fim > n) fim = n;
  return json.substring(pos, fim);
}

void diagnosticarChave(const String &corpo, const char *chave, bool lido, const String &valor) {
  bool ehNome = (strcmp(chave, CHAVE_NOME) == 0);
  String bruto = valorBrutoJson(corpo, chave);
  Serial.print("[TB]   ");
  Serial.print(chave);
  Serial.print(": ");
  if (bruto.length() == 0) {
    Serial.println("(nao veio nesta mensagem)");
    return;
  }
  if (!lido) {
    Serial.print("IGNORADO - chegou como ");
    Serial.print(bruto);
    Serial.println(" (sem aspas: numero ou outro tipo).");
    Serial.println("[TB]     O gateway so aceita texto. Zeros a esquerda ja se perderam no ThingsBoard.");
    Serial.println("[TB]     Salve o atributo como String (com aspas, ex.: \"0001\").");
    return;
  }
  String usado = ehNome ? prepararNome(valor) : prepararMensagem(valor);
  Serial.print("bruto=");
  Serial.print(bruto);
  Serial.print("  lido=\"");
  Serial.print(valor);
  Serial.print("\"  usado=\"");
  Serial.print(usado);
  Serial.println("\"");
  if (usado != valor) {
    Serial.print("[TB]     Aviso: texto limpo/cortado (aspas, '|', acentos ou acima de ");
    Serial.print(ehNome ? NOME_MAX_BYTES : VALOR_MAX_BYTES);
    Serial.println(" bytes).");
  }
  if (usado.length() == 0) {
    Serial.println(ehNome ? "[TB]     Nome vazio: sera usado o padrao " NOME_PADRAO "."
                          : "[TB]     Valor vazio: sera ignorado.");
  }
}

void mostrarAjuda() {
  Serial.println("[SERIAL] Comandos:");
  Serial.println("[SERIAL]   0001        envia o valor (nome fica como esta)");
  Serial.println("[SERIAL]   0001|AR     envia valor e nome");
  Serial.println("[SERIAL]   nome=AR     envia so o nome");
  Serial.println("[SERIAL]   reenviar    reenvia o alvo atual com SEQ nova");
  Serial.println("[SERIAL]   status / ?  mostra o estado");
  Serial.println("[SERIAL]   ajuda       esta lista");
}

void mostrarStatus() {
  Serial.println("[STATUS] ---------------------------------------");
  Serial.print("[STATUS] Alvo: valor=\"");
  Serial.print(mensagemAlvo);
  Serial.print("\" nome=\"");
  Serial.print(nomeAlvo);
  Serial.print("\" seq=");
  Serial.println(seqAtual);
  Serial.print("[STATUS] Comando: ");
  if (comandoPendente) {
    Serial.print("PENDENTE, tentativa ");
    Serial.print(tentativas);
    Serial.print("/");
    Serial.println(MAX_TENTATIVAS);
  } else if (confirmada) {
    Serial.println("confirmado (o beacon anuncia o alvo)");
  } else {
    Serial.println("SEM CONFIRMACAO (tentativas esgotadas)");
  }
  Serial.print("[STATUS] Ultimo alerta ouvido: ");
  if (ultimoAlertaMs == 0) {
    Serial.println("nenhum desde a partida");
  } else {
    Serial.print("valor=\"");
    Serial.print(ultimoValorOuvido);
    Serial.print("\" nome=\"");
    Serial.print(ultimoNomeOuvido);
    Serial.print("\" via ");
    Serial.print(ultimaOrigemOuvida);
    Serial.print(", ha ");
    Serial.print((millis() - ultimoAlertaMs) / 1000);
    Serial.println(" s");
  }
  Serial.print("[STATUS] WiFi: ");
  Serial.print(WiFi.status() == WL_CONNECTED ? "conectado" : "DESCONECTADO");
  Serial.print(" | MQTT: ");
  Serial.println(mqttClient.connected() ? "conectado" : "DESCONECTADO");
  Serial.println("[STATUS] ---------------------------------------");
}

// ---------- telemetria ----------

void publicarStatus(const char *status) {
  String payload = String("{\"comandoStatus\":\"") + status +
                   "\",\"comandoSeq\":" + String(seqAtual) +
                   ",\"mensagemAlvo\":\"" + mensagemAlvo +
                   "\",\"nomeAlvo\":\"" + nomeAlvo + "\"}";
  Serial.print("Status: ");
  Serial.println(payload);
  mqttClient.publish(MQTT_TOPIC, payload.c_str());
}

void publicarAlerta(const String &zona, const String &codigo, const String &mensagem) {
  String payload = "{\"alerta\":true,\"zona\":\"" + zona +
                   "\",\"codigo\":\"" + codigo +
                   "\",\"mensagem\":\"" + mensagem + "\"}";
  Serial.print("Publicando no ThingsBoard: ");
  Serial.println(payload);
  mqttClient.publish(MQTT_TOPIC, payload.c_str());
}

// ---------- comando: novo alvo (ThingsBoard ou Serial) ----------

void novoAlvo(const String &valorBruto, const String &nomeBruto, const char *origem) {
  String v = prepararMensagem(valorBruto);
  String n = prepararNome(nomeBruto);
  if (v.length() == 0) {
    Serial.print("[CMD] Valor vazio ignorado (origem: ");
    Serial.print(origem);
    Serial.println(").");
    return;
  }
  if (n.length() == 0) n = NOME_PADRAO;                  // nome vazio = padrão
  if (v == mensagemAlvo && n == nomeAlvo && (confirmada || comandoPendente)) {   // nada novo
    Serial.print("[CMD] Sem mudanca (origem: ");
    Serial.print(origem);
    Serial.print("): valor=\"");
    Serial.print(v);
    Serial.print("\" nome=\"");
    Serial.print(n);
    Serial.println(comandoPendente ? "\" ja esta sendo enviado." : "\" ja confirmado no beacon.");
    return;
  }

  if (v != mensagemAlvo || n != nomeAlvo) {
    mensagemAlvo = v;
    nomeAlvo     = n;
    seqAtual++;
    prefs.putUChar("seq", seqAtual);
    prefs.putString("alvo", mensagemAlvo);
    prefs.putString("nome", nomeAlvo);
  }
  confirmada = false;
  prefs.putBool("conf", false);
  comandoPendente  = true;
  tentativas       = 0;
  proximaEmissaoMs = 0;     // emite já
  Serial.print("Novo alvo para o beacon: valor=");
  Serial.print(mensagemAlvo);
  Serial.print(" nome=");
  Serial.print(nomeAlvo);
  Serial.print(" (seq ");
  Serial.print(seqAtual);
  Serial.print(", origem: ");
  Serial.print(origem);
  Serial.println(")");
}

void reenviarAlvo() {
  if (mensagemAlvo.length() == 0) {
    Serial.println("[SERIAL] Nao ha alvo para reenviar. Digite um valor (ex.: 0001).");
    return;
  }
  seqAtual++;                              // SEQ nova: o beacon aceita de novo
  prefs.putUChar("seq", seqAtual);
  confirmada = false;
  prefs.putBool("conf", false);
  comandoPendente  = true;
  tentativas       = 0;
  proximaEmissaoMs = 0;
  Serial.print("[CMD] Reenvio do alvo atual: valor=");
  Serial.print(mensagemAlvo);
  Serial.print(" nome=");
  Serial.print(nomeAlvo);
  Serial.print(" (seq ");
  Serial.print(seqAtual);
  Serial.println(", origem: Serial)");
}

void aoReceberMqtt(char *topico, byte *payload, unsigned int tamanho) {
  String corpo;
  corpo.reserve(tamanho);
  for (unsigned int i = 0; i < tamanho; i++) corpo += (char)payload[i];

  Serial.println();
  Serial.println("[TB] ===== Mensagem do ThingsBoard =====");
  Serial.print("[TB] Topico: ");
  Serial.println(topico);
  Serial.print("[TB] Conteudo bruto: ");
  Serial.println(corpo);

  // O update pode trazer só uma das chaves; a que faltar mantém o valor atual
  String v, n;
  bool temV = extrairString(corpo, CHAVE_ATRIBUTO, v);
  bool temN = extrairString(corpo, CHAVE_NOME, n);
  diagnosticarChave(corpo, CHAVE_ATRIBUTO, temV, v);
  diagnosticarChave(corpo, CHAVE_NOME, temN, n);
  if (temV || temN) {
    novoAlvo(temV ? v : mensagemAlvo, temN ? n : nomeAlvo, "ThingsBoard");
  } else {
    Serial.println("[TB] Nenhuma chave aproveitavel nesta mensagem; nada muda.");
  }
}

// ---------- comandos digitados no Monitor Serial ----------

void executarComandoSerial(const String &linha) {
  Serial.print("[SERIAL] Recebido: \"");
  Serial.print(linha);
  Serial.println("\"");

  String minusc = linha;
  minusc.toLowerCase();
  if (minusc == "?" || minusc == "status") { mostrarStatus(); return; }
  if (minusc == "ajuda" || minusc == "help") { mostrarAjuda(); return; }
  if (minusc == "reenviar") { reenviarAlvo(); return; }

  if (minusc.startsWith("nome=")) {
    if (mensagemAlvo.length() == 0) {
      Serial.println("[SERIAL] Defina primeiro o valor (ex.: 0001 ou 0001|AR).");
      return;
    }
    novoAlvo(mensagemAlvo, linha.substring(5), "Serial");
  } else {
    int sep = linha.indexOf(SEPARADOR);
    if (sep >= 0) novoAlvo(linha.substring(0, sep), linha.substring(sep + 1), "Serial");
    else          novoAlvo(linha, nomeAlvo, "Serial");
  }
  Serial.println("[SERIAL] Lembrete: o ThingsBoard nao muda; ao reconectar, o valor dele volta a valer.");
}

// Lê o Monitor Serial. Aceita Enter (\n, \r\n) e também "Sem final de linha":
// nesse caso a linha é processada após 300 ms sem novos caracteres.
void processarSerial() {
  while (Serial.available()) {
    char c = (char)Serial.read();
    ultimoCaractereMs = millis();
    if (c == '\r' || c == '\n') {
      String linha = linhaSerial;
      linhaSerial = "";
      linha.trim();
      if (linha.length() > 0) executarComandoSerial(linha);
      continue;
    }
    if (linhaSerial.length() < 64) linhaSerial += c;
  }
  if (linhaSerial.length() > 0 && millis() - ultimoCaractereMs > 300) {
    String linha = linhaSerial;
    linhaSerial = "";
    linha.trim();
    if (linha.length() > 0) executarComandoSerial(linha);
  }
}

// ---------- comando: emissão BLE ----------

void emitirComando() {
  uint8_t seqEmitida = seqAtual;
  String seqHex = hex2(seqEmitida);
  String conteudo = mensagemAlvo + String(SEPARADOR) + nomeAlvo;       // "valor|nome"
  String nome   = seqHex + calcularTag(seqHex, conteudo) + conteudo;

  BLEAdvertisementData advData;
  advData.setName(nome.c_str());
  advData.setCompleteServices(BLEUUID(UUID_COMANDO));   // sem flags: 31 bytes no total
  pAdvertising->setAdvertisementData(advData);
  pAdvertising->setScanResponse(false);

  tentativas++;
  Serial.print("Emitindo comando (tentativa ");
  Serial.print(tentativas);
  Serial.print("/");
  Serial.print(MAX_TENTATIVAS);
  Serial.print("): ");
  Serial.println(nome);
  Serial.print("[CMD]   SEQ=");
  Serial.print(seqHex);
  Serial.print(" TAG=");
  Serial.print(nome.substring(2, 7));
  Serial.print(" conteudo=\"");
  Serial.print(conteudo);
  Serial.print("\" (");
  Serial.print(nome.length());
  Serial.println(" bytes no nome BLE, max 25). No ar por 30 s...");

  BLEDevice::startAdvertising();
  unsigned long fim = millis() + DURACAO_COMANDO_MS;
  while ((long)(fim - millis()) > 0) {      // mantém o MQTT vivo durante a emissão
    mqttClient.loop();
    processarSerial();
    delay(50);
  }
  BLEDevice::stopAdvertising();
  Serial.println("[CMD]   Emissao encerrada. Escutando o beacon para confirmar...");

  // Se chegou um alvo novo durante a emissão, ele já está agendado para agora
  if (seqAtual == seqEmitida) proximaEmissaoMs = millis() + REEMISSAO_MS;
}

// ---------- scan (alerta + confirmação) ----------

class MeuCallbackDeScan : public BLEAdvertisedDeviceCallbacks {
  void onResult(BLEAdvertisedDevice advertisedDevice) override {
    String valor;
    if (!lerServiceData(advertisedDevice, UUID_ALERTA, valor)) return;   // não é alerta
    if (valor.length() == 0) return;

    String nome = advertisedDevice.haveName()
                    ? String(advertisedDevice.getName().c_str())
                    : String("");
    bool viaRelay = advertisedDevice.haveServiceUUID() &&
                    advertisedDevice.isAdvertisingService(BLEUUID(UUID_RELAY));
    String origem = viaRelay ? "relay" : "beacon";

    Serial.print("[ALERTA] via ");
    Serial.print(origem);
    Serial.print(" (RSSI ");
    Serial.print(advertisedDevice.getRSSI());
    Serial.print(" dBm): valor=\"");
    Serial.print(limparTexto(valor));
    Serial.print("\" nome=\"");
    Serial.print(limparTexto(nome));
    Serial.println("\"");

    zonaDetectada     = limparTexto(extrairZona(nome));
    mensagemDetectada = limparTexto(nome);
    codigoDetectado   = limparTexto(valor);
    beaconDetectado   = true;

    ultimoValorOuvido  = codigoDetectado;
    ultimoNomeOuvido   = mensagemDetectada;
    ultimaOrigemOuvida = origem;
    ultimoAlertaMs     = millis();

    // Confirmação: o beacon já anuncia o valor alvo (Service Data) e o nome alvo
    if (comandoPendente && mensagemAlvo.length() > 0) {
      bool valorOk = (valor == mensagemAlvo);
      bool nomeOk  = (limparTexto(nome) == nomeAlvo);
      if (valorOk && nomeOk) {
        confirmou = true;
      } else {
        Serial.print("[CMD]   Ainda nao confere -> valor: ");
        Serial.print(valorOk ? "OK" : ("esperado \"" + mensagemAlvo + "\""));
        Serial.print(" | nome: ");
        Serial.println(nomeOk ? "OK" : ("esperado \"" + nomeAlvo + "\""));
      }
    }
  }
};

// ---------- Wi-Fi / MQTT ----------

void ledLigado(bool ligado) {
  digitalWrite(PINO_LED, (ligado == (LED_ATIVO_ALTO == 1)) ? HIGH : LOW);
}

void piscarLed(int vezes) {
  for (int i = 0; i < vezes; i++) {
    ledLigado(true);
    delay(100);
    ledLigado(false);
    delay(100);
  }
}

void conectarWiFi() {
  Serial.print("Conectando ao WiFi");
  WiFi.begin(WIFI_SSID, WIFI_SENHA);
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.println(" conectado!");
  Serial.print("IP: ");
  Serial.println(WiFi.localIP());
  Serial.print("[WIFI] Piscando o LED (GPIO ");
  Serial.print(PINO_LED);
  Serial.print(") ");
  Serial.print(PISCADAS_WIFI);
  Serial.println(" vezes.");
  piscarLed(PISCADAS_WIFI);          // ~2 s
}

void conectarMQTT() {
  mqttClient.setServer(MQTT_HOST, MQTT_PORT);

  while (!mqttClient.connected()) {
    Serial.print("Conectando ao ThingsBoard...");
    if (mqttClient.connect("ESP32GatewayAlerta", THINGSBOARD_TOKEN, "")) {
      Serial.println(" conectado!");
      mqttClient.subscribe(TOPICO_ATRIBUTOS);    // mudanças futuras
      mqttClient.subscribe(TOPICO_RESPOSTA);     // resposta ao pedido abaixo
      String pedido = String("{\"sharedKeys\":\"") + CHAVE_ATRIBUTO + "," + CHAVE_NOME + "\"}";
      mqttClient.publish(TOPICO_PEDIDO, pedido.c_str());   // valor atual
      Serial.println("[TB] Pedido dos atributos atuais enviado (resposta aparece como [TB] ...).");
    } else {
      Serial.print(" falhou, rc=");
      Serial.print(mqttClient.state());
      Serial.println(" tentando novamente em 3s...");
      delay(3000);
    }
  }
}

void setup() {
  Serial.begin(115200);
  delay(1000);
  Serial.println("Iniciando ESP32 Gateway (alerta + comando de mensagem) - versao com diagnostico...");

  // Estado do comando guardado na memória
  prefs.begin("gateway", false);
  mensagemAlvo = prefs.getString("alvo", "");
  nomeAlvo     = prefs.getString("nome", NOME_PADRAO);
  if (nomeAlvo.length() == 0) nomeAlvo = NOME_PADRAO;
  seqAtual     = prefs.getUChar("seq", 0);
  confirmada   = prefs.getBool("conf", true);
  if (!confirmada && mensagemAlvo.length() > 0) {    // retoma comando não confirmado
    comandoPendente  = true;
    tentativas       = 0;
    proximaEmissaoMs = 0;
  }
  Serial.print("Alvo guardado: \"");
  Serial.print(mensagemAlvo);
  Serial.print("\" nome=\"");
  Serial.print(nomeAlvo);
  Serial.print("\" seq=");
  Serial.print(seqAtual);
  Serial.println(confirmada ? " (confirmado)" : " (PENDENTE)");

  pinMode(PINO_LED, OUTPUT);
  ledLigado(false);
  conectarWiFi();

  mqttClient.setBufferSize(512);
  mqttClient.setCallback(aoReceberMqtt);
  conectarMQTT();

  BLEDevice::init("ESP32-Gateway");
  pAdvertising = BLEDevice::getAdvertising();
  pBLEScan = BLEDevice::getScan();
  pBLEScan->setAdvertisedDeviceCallbacks(new MeuCallbackDeScan());
  pBLEScan->setActiveScan(false);
  pBLEScan->setInterval(100);
  pBLEScan->setWindow(99);

  Serial.println("Configuracao concluida. Iniciando ciclo de escaneamento...");
  mostrarAjuda();
}

void loop() {
  if (!mqttClient.connected()) {
    conectarMQTT();
  }
  mqttClient.loop();
  processarSerial();

  // --- Descida: emitir (ou reemitir) o comando pendente ---
  if (comandoPendente && (long)(millis() - proximaEmissaoMs) >= 0) {
    if (tentativas >= MAX_TENTATIVAS) {
      comandoPendente = false;
      Serial.println("[CMD] !!!!! Beacon NAO confirmou apos todas as tentativas !!!!!");
      Serial.print("[CMD]   Alvo: valor=\"");
      Serial.print(mensagemAlvo);
      Serial.print("\" nome=\"");
      Serial.print(nomeAlvo);
      Serial.println("\"");
      Serial.print("[CMD]   Ultimo alerta ouvido: ");
      if (ultimoAlertaMs == 0) {
        Serial.println("nenhum (beacon e relay fora do alcance do gateway?)");
      } else {
        Serial.print("valor=\"");
        Serial.print(ultimoValorOuvido);
        Serial.print("\" nome=\"");
        Serial.print(ultimoNomeOuvido);
        Serial.print("\" via ");
        Serial.println(ultimaOrigemOuvida);
      }
      Serial.println("[CMD]   Para tentar de novo: salve o atributo no ThingsBoard ou digite 'reenviar'.");
      publicarStatus("sem_confirmacao");
    } else {
      publicarStatus(tentativas == 0 ? "enviando" : "reenviando");
      emitirComando();
    }
  }

  // --- Subida: escanear alertas ---
  Serial.println("Escaneando...");
  beaconDetectado = false;
  pBLEScan->start(DURACAO_SCAN_S, false);
  pBLEScan->clearResults();

  if (beaconDetectado) {
    publicarAlerta(zonaDetectada, codigoDetectado, mensagemDetectada);
  } else {
    Serial.println("Nenhum alerta detectado neste ciclo.");
  }

  // --- Confirmação do comando ---
  if (confirmou) {
    confirmou       = false;
    comandoPendente = false;
    confirmada      = true;
    prefs.putBool("conf", true);
    Serial.println("[CMD] ***** BEACON ATUALIZADO *****");
    Serial.print("[CMD]   valor=\"");
    Serial.print(mensagemAlvo);
    Serial.print("\" nome=\"");
    Serial.print(nomeAlvo);
    Serial.print("\" (seq ");
    Serial.print(seqAtual);
    Serial.print(", ouvido via ");
    Serial.print(ultimaOrigemOuvida);
    Serial.print(", apos ");
    Serial.print(tentativas);
    Serial.println(" emissao(oes))");
    publicarStatus("confirmado");
  } else if (comandoPendente && tentativas > 0) {
    long faltam = (long)(proximaEmissaoMs - millis());
    if (faltam < 0) faltam = 0;
    Serial.print("[CMD] Aguardando confirmacao (tentativa ");
    Serial.print(tentativas);
    Serial.print("/");
    Serial.print(MAX_TENTATIVAS);
    Serial.print("), proxima emissao em ");
    Serial.print(faltam / 1000);
    Serial.println(" s");
  }
}
