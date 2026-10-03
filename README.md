# Limpeza

App Android de limpeza (versão 1.1).

## O que ele faz
- Mostra o cache do próprio app e o espaço livre do aparelho.
- **Liberar cache de todos os apps**: pede ao Android (StorageManager, Android 8+) que apague caches de outros apps para abrir espaço. O sistema decide quanto apagar.
- **Limpar arquivos temporários**: procura no armazenamento compartilhado arquivos .tmp, .log, downloads incompletos, miniaturas (.thumbnails) e pastas vazias, todos com mais de 24h. Mostra o resumo e pede confirmação antes de apagar. Exige a permissão "acesso a todos os arquivos".
- Limpar o cache do próprio app e abrir as configurações de armazenamento do Android.

## Limites
Sem root, um app comum não acessa as pastas internas de outros apps nem do sistema.
