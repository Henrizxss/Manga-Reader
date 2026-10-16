# Leitor de Mangá

Leitor Android offline para PDFs. O progresso de cada arquivo e a direção de leitura são guardados nas preferências locais do app; não há conta, servidor ou banco de dados.

## Adicionar os PDFs

Copie os arquivos PDF para `app/src/main/assets/manga/`. Cada PDF aparece como um volume na biblioteca. Para que a ordem seja previsível, numere os arquivos com zeros à esquerda, por exemplo:

```text
app/src/main/assets/manga/
  001 - Volume 1.pdf
  002 - Volume 2.pdf
  003 - Volume 3.pdf
```

Os PDFs serão incluídos no APK. O tamanho do APK aumentará de acordo com o tamanho total dos arquivos. Para trocar ou acrescentar volumes, adicione os PDFs à pasta e gere um APK novo.

Os nomes atuais dos arquivos são reconhecidos automaticamente: a biblioteca mostra **Tokyo Ghoul, volumes 1–14**, seguido de **Tokyo Ghoul:re, volumes 1–16**. Os números dos volumes são ordenados numericamente; não é necessário renomear esses PDFs.

## Gerar e instalar o APK

1. Instale o Android Studio e, no SDK Manager, instale Android SDK Platform 35. O build requer JDK 17; o Android Studio inclui um JDK apropriado.
2. Copie os PDFs para `app/src/main/assets/manga/` e abra esta pasta no Android Studio. Aguarde a sincronização do Gradle.
3. Selecione **Build > Build Bundle(s) / APK(s) > Build APK(s)**. Alternativamente, no PowerShell nesta pasta, execute `.\gradlew.bat assembleDebug --max-workers=2`.
4. O APK de depuração será criado em `app/build/outputs/apk/debug/app-debug.apk`. Transfira-o para o celular, abra o arquivo e permita a instalação quando solicitado.

Para testar no emulador sem empacotar os PDFs inteiros da coleção, selecione um único volume, por exemplo: `.\gradlew.bat installDebug --max-workers=2 "-PpreviewPdf=Tokyo Ghoul Volume 1 - PDF Room.pdf"`. O APK de pré-visualização conterá somente esse volume; um build normal, sem `previewPdf`, continua incluindo todos os PDFs.

## Leitura

- Toque em um volume para abrir a última página visitada.
- Deslize ou toque nas laterais da página, use os botões ou arraste o controle para mudar de página. As páginas são renderizadas em segundo plano e aparecem com uma transição suave. No fim do volume, o leitor oferece o próximo PDF automaticamente.
- Aproxime dois dedos para ampliar, arraste para mover a página ampliada, toque duas vezes para alternar o zoom e use **Ajustar** para voltar à página inteira.
- Use **Ir para página** para saltar a uma página específica e os atalhos **Mangá anterior/Próximo mangá** para trocar de PDF sem voltar à biblioteca.
- Toque em **Ocultar controles** para recolher o painel e ganhar espaço para a página; toque novamente para expandi-lo. Essa preferência fica salva.
- Use o botão **⛶** na barra superior para ativar ou sair da tela cheia. O botão Voltar também sai da tela cheia antes de fechar o volume.
- A biblioteca, os volumes e os botões entram com animações discretas; os cartões têm feedback tátil visual ao serem abertos.
- Ative **Manter tela ligada durante a leitura** na biblioteca para evitar que o celular bloqueie enquanto você lê.
- Cada página aberta é marcada como lida, e a biblioteca mostra o progresso do volume.
- Use o botão no topo da biblioteca para alternar a direção de leitura.
- Use **Zerar** dentro de um volume para apagar o progresso daquele volume.
- A tela inicial exibe o perfil local mockado **café pilao** e a imagem de perfil fornecida; não existe autenticação.
