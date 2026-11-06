# TP3 AEDS III — Carros

Projeto em **Java 17+**, sem Maven, com 100.000 carros reais.
Preserva o CRUD, a ordenação externa e os quatro índices dos TP1/TP2:
B+, Hash Estendido e listas invertidas por ano e características.
O TP3 acrescenta compressão e casamento de padrões.

**Integrantes:** Gabriel Benicio Fonseca e Rhayner Martins.

## Base de dados

O recorte do [Used Cars Dataset — Craigslist](https://www.kaggle.com/datasets/austinreese/craigslist-carstrucks-data)
está em `data/base.csv`. Os registros são armazenados em `data/dados.db`;
o nome reúne fabricante e modelo.

## Executar

Na raiz do projeto:

```bash
mkdir -p out
javac -encoding UTF-8 -d out -sourcepath src src/*.java
java -Xmx512m -cp out Main
```

No Windows, use `compilar.bat` e `executar.bat`.
Na primeira execução, carregue o CSV pela **opção 1**. Para o CRUD indexado,
entre em **9 — TP2** e construa os índices na opção **6**
(ordem B+ sugerida: 32; população inicial: 100000).

## TP3 — opção 10 do menu principal

1. Comprimir todos os bytes de `dados.db` com **Huffman e LZW**, sobre o mesmo snapshot.
2. Listar versões de compressão.
3. Descomprimir e restaurar uma versão, validando tamanho, SHA-256 e igualdade dos bytes.
4. Buscar no nome com **KMP**.
5. Buscar no nome com **BMH**.
6. Comparar resultados e tempos das buscas.

As versões ficam em `data/compressao/`, com os nomes `dados.dbHuffmanCompressaoX`
e `dados.dbLZWCompressaoX`, sem sobrescrever arquivos anteriores.
A tela mostra tamanhos, redução percentual e tempos reais.
LZW usa dicionário inicial de 256 bytes, códigos de 12 bits e reinicialização explícita.

As buscas consideram registros ativos, ignoram maiúsculas/minúsculas,
preservam acentos e exibem 20 resultados por página.
A restauração exige confirmação com `SIM`, descarta alterações posteriores ao snapshot
e reconstrói os quatro índices. Em caso de falha, preserve o backup e reconstrua
os índices em **TP2 → opção 6**. Use uma aplicação por base.
Dados binários, índices, compressões e temporários são gerados e ignorados pelo Git.

## Testes

Após compilar, execute `java -Xmx512m -cp out NOME` para as suítes, nesta ordem:

- Anteriores: `TesteTP1`, `TesteBPlus`, `TesteHash`, `TesteListas`, `TesteFase2`, `TesteTP2`.
- TP3: `TesteCompressao`, `TestePadroes`, `TesteTP3`.

Integração com os 100.000 registros, em cópia isolada em `build-test/`:

```bash
java -Xmx512m -cp out TesteTP3 --base-real
```

No Windows, `testar.bat` executa as nove suítes funcionais; o teste real é separado.
Os testes cobrem integridade, corrupção, versões, restauração, buscas, índices,
CRUD e reabertura em outra JVM. Os scripts Windows não foram executados no macOS.

## Documentação

- [Decisões de implementação do TP3](docs/TP3_DECISOES.md).
- [Resultados e medições reais](RESULTADO_TESTES.txt).
- Histórico do TP2: [Fase 1](docs/TP2_FASE1.md) e [Fase 2](docs/TP2_FASE2.md).
