# Limpeza

App Android de limpeza de armazenamento (versão 1.1).

## O que ele faz
- Mostra o cache do próprio app e o espaço livre do aparelho.
- **Liberar cache de todos os apps**: pede ao Android (StorageManager, Android 8+) que apague caches de outros apps para abrir espaço. O sistema decide quanto apagar.
- **Limpar arquivos temporários**: procura no armazenamento compartilhado arquivos `.tmp`, `.log`, downloads incompletos, miniaturas (`.thumbnails`) e pastas vazias, todos com mais de 24h. Mostra o resumo e pede confirmação antes de apagar. Exige a permissão "acesso a todos os arquivos".
- Limpar o cache do próprio app e abrir as configurações de armazenamento do Android.

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
