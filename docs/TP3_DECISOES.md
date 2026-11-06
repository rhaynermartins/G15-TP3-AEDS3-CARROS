# TP3 — Decisões de implementação

## Continuidade

Base: main do TP2, commit b459f485d449beeb5964c9e22a9093d271f8570d.
Entidade, serialização, CSV, CRUD e algoritmos de ordenação/índices permanecem.
O TP3 acrescenta a opção 10, os compressores e o casamento sobre nomes ativos.
Java 17 ou superior (validado com Temurin 25.0.2); não utiliza Maven nem bibliotecas
de compressão ou pesquisa de padrões. Compilação pelo javac existente no projeto.

## Huffman

Alfabeto de 256 bytes, contagem de frequências e árvore binária construída com
fila de prioridade. O desempate usa o menor símbolo da subárvore, permitindo
reconstrução determinística. As folhas geram os códigos; o fluxo é escrito bit a
bit, MSB primeiro. A tabela de 256 frequências long ocupa 2.048 bytes.
Entrada vazia tem tabela zerada; símbolo único recebe código 0 de um bit.
O decodificador verifica soma e frequências efetivas, além do tamanho e SHA-256.
Não transforma o binário em texto nem seleciona campos: preserva TODOS os bytes.

## LZW

Dicionário inicial: códigos 0..255 representam os bytes; 256 significa CLEAR e
257 significa END. Sequências usam 258..4095. Todos os códigos ocupam **12 bits
fixos**, empacotados em bytes; portanto não há transições de largura.
Quando uma sequência precisa ser acrescentada a um dicionário cheio, o encoder
emite o prefixo e CLEAR, reinicia o dicionário e conserva o byte corrente como
novo prefixo. O decoder reinicia ao receber CLEAR. O caso KwKwK é tratado
explicitamente. O decoder usa prefixos/sufixos e uma pilha limitada a 4.096 bytes.
Esta escolha limita memória e simplifica sincronização, com possível perda de
taxa em relação a dicionários maiores. Arquivos pequenos podem crescer.

## Contêiner e versões

Cabeçalho comum de **53 bytes**: magic TP3C (4), formato 1 (4), algoritmo (1),
versão de compressão X (4), tamanho original (8) e SHA-256 original (32).
Inteiros são big-endian. Huffman usa algoritmo 0; LZW usa 1.
O corpo contém a tabela/fluxo Huffman ou os códigos LZW. O padding final é zero;
bytes excedentes, truncamentos e expansões além do tamanho declarado são recusados.

Nomes oficiais, sem extensão adicional:
- data/compressao/dados.dbHuffmanCompressao1
- data/compressao/dados.dbLZWCompressao1

Os dois métodos recebem o **mesmo snapshot binário**, copiado sob lock.
Novas versões usam o maior número existente + 1 e CREATE_NEW/move sem substituição.
Um marcador de publicação identifica pares interrompidos; suas versões não são
reutilizadas. A lista distingue par disponível de versão incompleta. A integridade
do par só é confirmada depois da descompressão. Não se promete troca atômica de
dois arquivos: o marcador e as validações tornam interrupções detectáveis.

## Medições

Ganho = (original - comprimido) / original * 100; inclui o cabeçalho completo.
Valor negativo é expansão; original vazio mostra n/a, pois a divisão seria indefinida.
Tempos individuais incluem hash, leitura/escrita e validações próprias do codec;
o snapshot e a publicação comuns ficam fora desses tempos. A tela compara
tamanho e velocidade separadamente e reconhece empates.
Os tempos de descompressão incluem a validação de cada resultado. A comparação
byte a byte entre as duas saídas e a reconstrução dos índices ocorrem depois.
São medições locais simples, afetadas por cache, JVM, disco e ordem de execução.

## Restauração e índices

Ambos os arquivos são descomprimidos separadamente em temporários. Confere-se
algoritmo, versão, tamanho, SHA-256 e igualdade byte a byte entre as saídas.
O usuário escolhe Huffman ou LZW, pode cancelar e confirma com SIM.
Só então a cópia escolhida é entregue ao GerenciadorIndices.

Antes da troca, também são verificados cabeçalho, IDs, lápides e registros da
base candidata. Faz-se backup sincronizado e grava-se o marcador de índices
pendentes ANTES de substituir dados.db. Prefere-se move atômico; há fallback
para move com substituição. Se a troca falhar, tenta-se recuperar o backup.
Depois da troca, reconstroem-se os quatro índices usando a ordem e população
inicial persistidas. Se ainda não existiam índices, os padrões são 32 e 100.000.
Uma falha nessa reconstrução conserva o backup e o marcador, bloqueando buscas
indexadas inclusive após reabrir. A recuperação usa TP2 / opção 6.
Em sucesso o backup temporário é removido. Compressão não reconstrói índices.

Restaurar é um retorno temporal: descarta mudanças posteriores e pode diminuir
ultimoId para o valor daquele snapshot. Isso é diferente de reutilizar IDs em
operações normais de Create/Delete.

## Concorrência

Main mantém um FileLock exclusivo lateral durante a sessão. Serviços TP3 e
escritas via GerenciadorIndices usam o mesmo lock, reentrante na mesma thread.
O lock é lateral porque o inode de dados.db muda durante restauração/ordenação.
Outra thread ou aplicação cooperante é recusada, sem aguardar indefinidamente.
O arquivo de lock não é apagado ao liberar, evitando trocar seu inode.
A assinatura da fonte é conferida novamente para detectar alterações externas.
Locks são cooperativos: não impedem um editor externo ou código legado que
escreva diretamente pelo DAO sem respeitar o protocolo. Use uma aplicação por base.

## KMP e BMH

Campo escolhido: nome, que já reúne fabricante/modelo. Só são percorridos carros
ativos, desserializados pelo DAO, e cada resultado mostra ID/nome.

KMP foi escolhido pela garantia linear: a tabela de prefixos reutiliza o que já
foi comparado sem retroceder no texto. BMH compara a janela da direita para a
esquerda e usa a tabela de deslocamentos do último caractere; pode saltar mais
em textos favoráveis, mas seu pior caso pode fazer mais comparações.
Ambos preparam a tabela uma vez por consulta e são implementados manualmente.

Normalização: minúsculas com Locale.ROOT nos nomes e no padrão; preserva acentos.
Comparação sobre unidades UTF-16 do Java. Padrão vazio é recusado; sobreposições
não duplicam o carro, pois o resultado é por registro, não por ocorrência.
A ordem física é preservada e a interface apresenta páginas de até 20 itens.
O tempo de busca inclui leitura, normalização e coleta de resultados, não só
comparações de caracteres. Os testes usam indexOf/contains como oráculos, nunca
a implementação de produção.

## Evidências e limites

TesteCompressao verifica 158 entradas por algoritmo, integridade e corrupção.
TestePadroes confronta 20.009 pares com o oráculo e verifica paginação no arquivo.
TesteTP3 verifica versões, restauração, falhas de escrita, bloqueios, recuperação,
fluxo real do menu e, com --base-real, os 100.000 carros em ambiente isolado.
A reabertura ocorre em outra JVM, sem reconstrução.
Resultados medidos estão em RESULTADO_TESTES.txt. Testes de falha simulam
interrupções/indisponibilidade; não representam um ensaio físico de queda de energia.
O vídeo pertence à Fase 2 e aguarda aprovação da Fase 1.
