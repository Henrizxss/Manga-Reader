# Leitor de Mangá

Leitor Android para PDFs locais ou selecionados no Google Drive. O progresso e as preferências são guardados no aparelho; não há backend, banco de dados ou configuração OAuth.

## Usar PDFs do Google Drive

Toque em **Adicionar PDFs** e use o seletor de arquivos do Android. Navegue até **Google Drive**, entre na sua conta caso seja solicitado, abra `Mangás` e selecione um ou vários PDFs (também pode selecionar PDFs em subpastas). Não é necessário criar um projeto no Google Cloud ou configurar OAuth; o app recebe acesso apenas aos arquivos escolhidos.

Os arquivos selecionados aparecem na biblioteca. **Baixar** guarda uma cópia no armazenamento privado do app para leitura offline; durante a cópia, o cartão do volume mostra uma barra de carregamento e o percentual. Tocar em um volume ainda não baixado inicia a cópia e abre o leitor quando termina. **Remover** tira o volume da biblioteca e apaga a cópia local, mas nunca exclui o arquivo do Drive. O progresso de leitura fica salvo no aparelho e é mantido ao remover e adicionar novamente o mesmo arquivo. Se a origem deixar de estar acessível, selecione o PDF novamente no Drive.

## Gerar e instalar o APK

1. Instale o Android Studio e, no SDK Manager, instale Android SDK Platform 35. O build requer JDK 17; o Android Studio inclui um JDK apropriado.
2. Abra esta pasta no Android Studio. Aguarde a sincronização do Gradle.
3. Selecione **Build > Build Bundle(s) / APK(s) > Build APK(s)**. Alternativamente, no PowerShell nesta pasta, execute `.\gradlew.bat assembleDebug --max-workers=2`.
4. O APK de depuração será criado em `app/build/outputs/apk/debug/app-debug.apk`. Transfira-o para o celular, abra o arquivo e permita a instalação quando solicitado.

Para instalar o app sem empacotar PDFs, execute `.\gradlew.bat installDebug --max-workers=2`. Uma prévia ainda pode incluir um único volume usando `.\gradlew.bat installDebug --max-workers=2 "-PpreviewPdf=Tokyo Ghoul Volume 1 - PDF Room.pdf"`.

## Leitura

- Em **Personalizar perfil e cor**, altere o nome exibido, escolha uma foto e ajuste livremente a cor de destaque pelos canais RGB. **Voltar ao padrão** restaura o perfil Café Pilão, a imagem original e a cor vermelha sem alterar o progresso ou as preferências de leitura. Essas personalizações ficam salvas localmente no aparelho.
- Toque em um volume para abrir a última página visitada.
- Deslize ou toque nas laterais da página, use os botões ou arraste o controle para mudar de página. As páginas são renderizadas em segundo plano e aparecem com uma transição suave. No fim do volume, o leitor oferece o próximo PDF automaticamente.
- Aproxime dois dedos para ampliar, arraste para mover a página ampliada, toque duas vezes para alternar o zoom e use **Ajustar** para voltar à página inteira.
- Use **Ir para página** para saltar a uma página específica e os atalhos **Mangá anterior/Próximo mangá** para trocar de PDF sem voltar à biblioteca.
- Toque em **Ocultar controles** para recolher o painel e ganhar espaço para a página; toque novamente para expandi-lo. Essa preferência fica salva.
- Use o botão **⛶** na barra superior para ativar ou sair da tela cheia. O botão Voltar também sai da tela cheia antes de fechar o volume.
- A biblioteca, os volumes e os botões entram com animações discretas; os cartões têm feedback tátil visual ao serem abertos.
- Ative **Manter tela ligada durante a leitura** na biblioteca para evitar que o celular bloqueie enquanto você lê.
- Cada página aberta é marcada como lida, e a biblioteca mostra o progresso do volume.
- Em **Gesto para avançar**, escolha deslizar da direita para a esquerda ou arrastar para baixo.
- Use **Zerar** dentro de um volume para apagar o progresso daquele volume.
- A tela inicial usa por padrão o perfil local **Café Pilão** e a imagem de perfil fornecida; nome, foto e cor de destaque podem ser personalizados na biblioteca. O acesso ao Drive passa pelo seletor oficial de arquivos do Android.
