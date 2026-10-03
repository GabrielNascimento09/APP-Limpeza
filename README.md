# Limpeza Android

Projeto Android Studio inspirado no `Limpeza.bat` enviado.

## O que ele faz
- Mostra o tamanho do cache privado do próprio aplicativo.
- Limpa o cache privado do aplicativo com um botão.
- Abre as configurações de armazenamento do Android para o usuário fazer a limpeza permitida pelo sistema.

## Importante
O Android moderno usa armazenamento isolado (sandbox). Um aplicativo comum não pode apagar silenciosamente o cache de outros aplicativos, nem acessar pastas internas do sistema como `Windows\Temp` ou `Prefetch`.

## Como abrir
1. Abra esta pasta no Android Studio.
2. Aguarde o Gradle sincronizar.
3. Conecte um celular Android ou use um emulador.
4. Execute o projeto.
