# Limpeza

App Android de limpeza de armazenamento e verificação de segurança (versão 1.4).

## O que ele faz
Um único botão, **Limpar tudo**, executa em sequência:
1. Limpa o cache do próprio app.
2. Pede ao Android (StorageManager, Android 8+) que apague caches de outros apps para abrir espaço. O sistema decide quanto apagar.
3. Procura no armazenamento compartilhado arquivos `.tmp`, `.log`, downloads incompletos, miniaturas (`.thumbnails`) e pastas vazias, todos com mais de 24h. Mostra o resumo e pede confirmação antes de apagar. Exige a permissão "acesso a todos os arquivos"; sem ela, o app limpa só o cache.

Há também um botão para abrir as configurações de armazenamento do Android.

## Verificar segurança
Tela separada, que funciona 100% no aparelho (sem internet e sem enviar nada para fora). Não é um antivírus:
1. **Apps instalados**: lista os apps instalados por você e aponta sinais de alerta: instalação fora de lojas conhecidas, serviço de acessibilidade ativo, leitura de notificações, administrador do dispositivo, acesso a SMS e chamadas, sobreposição de tela, instalação de outros apps, entre outros. Cada app recebe um nível (baixo, atenção ou alto) e dá para abrir as configurações dele para desinstalar.
2. **APKs baixados**: procura arquivos `.apk` no armazenamento, analisa as permissões que eles pedem e permite apagar o arquivo antes de instalar. Exige a permissão "acesso a todos os arquivos".
3. **Estado do aparelho**: patch de segurança, bloqueio de tela e depuração USB.

### Limites
- Sinais de alerta não provam que um app é malicioso; muitos apps legítimos pedem permissões sensíveis.
- Não há base de assinaturas de malware nem monitoramento em tempo real.

## Aviso importante
Este app **apaga arquivos de forma permanente** (eles não vão para a lixeira). Use por sua conta e risco, e leia sempre o resumo antes de confirmar. O projeto é fornecido "como está", sem garantia, conforme a licença MIT. Ele foi escrito com ajuda de IA e ainda não passou por testes extensivos em aparelhos reais.

Fotos e vídeos comuns não são apagados: o app só remove os tipos de arquivo listados acima.

## Limites
Sem root, um app comum não acessa as pastas internas de outros apps nem do sistema.

## Como gerar o APK
**Pelo GitHub:** aba **Actions → Build APK → Run workflow**. Ao terminar, baixe o APK em **Artifacts**.

**Pelo Android Studio:** abra a pasta do projeto, aguarde o Gradle sincronizar e use **Build → Build APK(s)**.

O APK gerado é de debug. Para instalar, permita "instalar apps desconhecidos" no celular.

## Licença
[MIT](LICENSE)
