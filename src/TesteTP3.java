import compressao.ArquivoComprimido;
import compressao.ArquivoComprimido.Algoritmo;
import dao.ArquivoSequencial;
import model.Carro;
import service.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Integração em cópias isoladas: nunca substitui data/dados.db do projeto. */
public class TesteTP3 {
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--reabrir")) {
            Path p = Path.of(args[1]);
            ArquivoSequencial dados = new ArquivoSequencial(p.resolve("data/dados.db"));
            GerenciadorIndices g = gerenciador(dados);
            validar(g);
            exigir(g.buscar(50000).equals(g.buscarHash(50000)), "Reabertura e seek");
            exigir(g.ordemConfigurada() == 32 && g.quantidadeInicial() == 100000, "Configuração reaberta");
            System.out.println("TP3 REABERTURA EM NOVA JVM: OK; sem reconstrução.");
            return;
        }
        Files.createDirectories(Path.of("build-test"));
        if (args.length == 1 && args[0].equals("--base-real")) { baseReal(); return; }
        pequeno();
        System.out.println("TODOS OS TESTES DE INTEGRAÇÃO TP3 PASSARAM.");
    }
    private static GerenciadorIndices gerenciador(ArquivoSequencial d) {
        return new GerenciadorIndices(d, d.getCaminho().resolveSibling("index/arvore_bplus.idx"));
    }
    private static GerenciadorCompressao compressao(ArquivoSequencial d, GerenciadorIndices g) {
        return new GerenciadorCompressao(d.getCaminho(), d.getCaminho().resolveSibling("compressao"), g);
    }
    private static Carro carro(String nome) {
        return new Carro(0, "", nome, LocalDate.of(2026,11,6), List.of("gas","automatic"), 2020);
    }
    @SuppressWarnings("try")
    private static void pequeno() throws Exception {
        Path p = Files.createTempDirectory(Path.of("build-test"), "tp3-integracao-");
        ArquivoSequencial d = new ArquivoSequencial(p.resolve("data/dados.db"));
        d.reinicializar(40);
        for (int i = 1; i <= 40; i++) { Carro c = carro("Toyota " + i); c.setId(i); d.appendComId(c); }
        GerenciadorIndices g = gerenciador(d); g.reconstruirTodos(4, 100);
        CrudIndexado crud = new CrudIndexado(g);
        int id = crud.criar(carro("Toyota demo")).id;
        Carro maior = crud.localizar(CrudIndexado.Metodo.HASH, id).getCarro();
        maior.setNome("Toyota demo com nome ampliado para deixar uma lápide");
        crud.atualizar(crud.localizar(CrudIndexado.Metodo.HASH, id), maior);
        crud.excluir(crud.localizar(CrudIndexado.Metodo.BPLUS, 2));
        byte[] v1 = Files.readAllBytes(d.getCaminho());
        int lapides = d.contarLapides(), ultimo = d.getUltimoId();
        GerenciadorCompressao c = compressao(d, g);
        exigir(c.comprimir().versao() == 1, "Versão 1");
        exigir(Arrays.equals(v1, Files.readAllBytes(d.getCaminho())), "Compressão não muda base");
        validar(g);
        crud.excluir(crud.localizar(CrudIndexado.Metodo.HASH, id));
        crud.criar(carro("Outro veículo"));
        exigir(c.comprimir().versao() == 2, "Versão 2");
        byte[] v2 = ArquivoComprimido.digest(c.arquivo(2, Algoritmo.LZW));
        try (var restaura = c.prepararRestauracao(1)) {
            System.out.println(restaura.resumo()); restaura.restaurar(Algoritmo.LZW);
        }
        exigir(Arrays.equals(v1, Files.readAllBytes(d.getCaminho())), "Versão 1 byte a byte");
        exigir(d.getUltimoId() == ultimo && d.contarLapides() == lapides, "Cabeçalho e lápides");
        exigir(Arrays.equals(v2, ArquivoComprimido.digest(c.arquivo(2, Algoritmo.LZW))), "Versão 2 preservada");
        exigir(g.ordemConfigurada() == 4 && g.quantidadeInicial() == 100, "Configurações preservadas");
        validar(g);
        crud = new CrudIndexado(g);
        int depois = crud.criar(carro("Depois da restauração")).id;
        exigir(depois == ultimo + 1, "Create indexado após restaurar");
        crud.excluir(crud.consultar(2020, "automatic").selecionar(depois));
        validar(g);

        byte[] antes = Files.readAllBytes(d.getCaminho());
        falha(() -> { try (var r = c.prepararRestauracao(999)) { r.restaurar(Algoritmo.Huffman); } });
        Path h1 = c.arquivo(1, Algoritmo.Huffman); byte[] salvo = Files.readAllBytes(h1);
        Files.write(h1, Arrays.copyOf(salvo, 30));
        falha(() -> { try (var r = c.prepararRestauracao(1)) { r.restaurar(Algoritmo.Huffman); } });
        exigir(Arrays.equals(antes, Files.readAllBytes(d.getCaminho())), "Truncado preserva base");
        Files.write(h1, salvo);
        byte[] errado = salvo.clone(); errado[12] = 2; Files.write(h1, errado);
        falha(() -> { try (var r = c.prepararRestauracao(1)) { r.restaurar(Algoritmo.Huffman); } });
        Files.write(h1, salvo);
        Path pasta = h1.getParent();
        Files.writeString(pasta.resolve(".versao-8.pendente"), "operação interrompida");
        exigir(c.listarVersoes().contains("incompleta"), "Interrupção visível");
        falha(() -> { try (var r = c.prepararRestauracao(8)) { r.restaurar(Algoritmo.LZW); } });
        exigir(c.comprimir().versao() == 9, "Reserva interrompida não reutilizada");
        exigir(Arrays.equals(antes, Files.readAllBytes(d.getCaminho())), "Falhas sem substituir base");
        Path bloqueada = p.resolve("destino-nao-diretorio"); Files.writeString(bloqueada, "preservar");
        falha(() -> new GerenciadorCompressao(d.getCaminho(), bloqueada, g).comprimir());
        exigir(Files.readString(bloqueada).equals("preservar"), "Falha de gravação preserva destino");
        validar(g);

        // Outra thread e outra JVM não podem escrever durante a preparação/uso exclusivo.
        try (BloqueioBase lock = BloqueioBase.adquirir(d.getCaminho())) {
            AtomicBoolean recusou = new AtomicBoolean();
            Thread thread = new Thread(() -> {
                try { g.criar(carro("Concorrente")); } catch (IOException esperado) { recusou.set(true); }
            });
            thread.start(); thread.join(); exigir(recusou.get(), "Escrita concorrente recusada");
            String saida = processo(p, "", "Main");
            exigir(saida.contains("Não foi possível iniciar"), "Segunda aplicação recusada");
        }

        // A falha após substituir dados é distinta de saída inválida: mantém backup e bloqueia índices.
        GerenciadorIndices falho = new GerenciadorIndices(d, d.getCaminho().resolveSibling("index/arvore_bplus.idx")) {
            @Override public void reconstruirTodos(int ordem, long inicial) throws IOException {
                throw new IOException("Falha de escrita simulada na reconstrução");
            }
        };
        GerenciadorCompressao cf = compressao(d, falho);
        falha(() -> { try (var r = cf.prepararRestauracao(1)) { r.restaurar(Algoritmo.Huffman); } });
        exigir(Arrays.equals(v1, Files.readAllBytes(d.getCaminho())), "Bytes válidos após falha dos índices");
        falha(() -> g.buscar(1));
        try (var filhos = Files.list(d.getCaminho().getParent())) {
            exigir(filhos.anyMatch(x -> x.getFileName().toString().startsWith("dados_pre_restauracao-")), "Backup preservado");
        }
        g.reconstruirTodos(); validar(g);

        String entrada = "10\n1\n2\n3\n1\n1\nSIM\n4\nToyota\nn\n5\nTOYOTA\nn\n6\nToyota\nn\n0\n9\n7\n0\n0\n";
        String menu = processo(p, entrada, "Main");
        exigir(menu.contains("TP3 — COMPRESSÃO") && menu.contains("Mesmos IDs e nomes: OK")
                && menu.contains("Base restaurada e quatro índices reconstruídos")
                && !menu.contains("não concluída") && !menu.contains("Exception"), "Fluxo real do menu");
        System.out.println("VERSÕES/RESTAURAÇÃO: nomes, 1/2, preservação, falta, truncado, metadados e interrupção: OK");
        System.out.println("INTEGRAÇÃO: rollback temporal, lápides, ultimoId, quatro índices, CRUD e configuração: OK");
        System.out.println("FALHAS/CONCORRÊNCIA: base preservada, backup, bloqueio, recuperação e segunda JVM: OK");
        System.out.println("MENU: compressão, versões, restauração confirmada, KMP, BMH, comparação e TP2: OK");
    }

    private static void baseReal() throws Exception {
        Path p = Files.createTempDirectory(Path.of("build-test"), "tp3-real-");
        ArquivoSequencial d = new ArquivoSequencial(p.resolve("data/dados.db"));
        Path csv = Path.of("data/base.csv");
        byte[] csvAntes = ArquivoComprimido.digest(csv);
        var imp = new Importador().carregarBase(csv, d.getCaminho());
        exigir(imp.importados == 100000 && imp.ignorados == 0, "Base oficial completa");
        GerenciadorIndices g = gerenciador(d); g.reconstruirTodos(32, 100000);
        GerenciadorCompressao c = compressao(d, g);
        byte[] original = ArquivoComprimido.digest(d.getCaminho());
        Path referencia = p.resolve("referencia-original.db"); Files.copy(d.getCaminho(), referencia);
        System.out.println("BASE REAL: ativos=" + d.contarAtivos() + "; bytes=" + Files.size(d.getCaminho()));
        System.out.println(c.comprimir());
        // A preparação verifica ambos os arquivos antes de qualquer substituição.
        var crud = new CrudIndexado(g);
        int novo = crud.criar(carro("Toyota TP3 temporário")).id;
        System.out.println(c.comprimir());
        Carro modificado = crud.localizar(CrudIndexado.Metodo.BPLUS, novo).getCarro();
        modificado.setNome("Toyota TP3 temporário com nome bem maior para realocação");
        crud.atualizar(crud.localizar(CrudIndexado.Metodo.BPLUS, novo), modificado);
        crud.excluir(crud.localizar(CrudIndexado.Metodo.HASH, 1));
        try (var r = c.prepararRestauracao(1)) { System.out.println(r.resumo()); r.restaurar(Algoritmo.Huffman); }
        exigir(Arrays.equals(original, ArquivoComprimido.digest(d.getCaminho()))
                && Files.mismatch(referencia, d.getCaminho()) == -1, "Base real restaurada exatamente");
        exigir(d.getUltimoId() == 100000 && d.contarLapides() == 0, "Cabeçalho/lápides originais");
        System.out.println("BASE REAL SHA-256=" + HexFormat.of().formatHex(original) + "; igualdade byte a byte: OK");
        validar(g);
        BuscaPadroes busca = new BuscaPadroes(d);
        List<Carro> oraculo = d.listarAtivos();
        for (String termo : List.of("Toyota", "FORD", "honda civic", "não-existe-TP3", "silverado")) {
            var a = busca.buscar(termo, BuscaPadroes.Metodo.KMP);
            var b = busca.buscar(termo, BuscaPadroes.Metodo.BMH);
            List<Integer> esperado = oraculo.stream().filter(x -> x.getNome().toLowerCase(Locale.ROOT)
                    .contains(termo.toLowerCase(Locale.ROOT))).map(Carro::getId).toList();
            exigir(a.itens().equals(b.itens()) && a.itens().stream().map(BuscaPadroes.Item::id).toList()
                    .equals(esperado), "KMP/BMH/oráculo real");
            System.out.println("PADRÃO=" + termo + "; resultados=" + a.total() + "; KMP_ns=" + a.nanos()
                    + "; BMH_ns=" + b.nanos() + "; ORACLE=OK");
        }
        int id = crud.criar(carro("CRUD após restauração")).id;
        Carro maior = crud.localizar(CrudIndexado.Metodo.HASH, id).getCarro();
        maior.setNome("CRUD após restauração com realocação do registro de demonstração");
        crud.atualizar(crud.localizar(CrudIndexado.Metodo.HASH, id), maior);
        crud.excluir(crud.consultar(2020, "automatic").selecionar(id));
        validar(g);
        System.out.println(processo(p, "", "TesteTP3", "--reabrir", p.toAbsolutePath().toString()));
        exigir(Arrays.equals(csvAntes, ArquivoComprimido.digest(csv)), "CSV oficial preservado");
        System.out.println("TP3 BASE REAL: OK; 100000 registros, dois algoritmos, duas versões, integridade, CRUD e reabertura.");
    }

    private static String processo(Path pasta, String entrada, String... classeEArgs) throws Exception {
        String cp = Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                .map(x -> Path.of(x).toAbsolutePath().toString()).collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
        List<String> comando = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-Xmx512m", "-cp", cp));
        comando.addAll(Arrays.asList(classeEArgs));
        Process processo = new ProcessBuilder(comando).directory(pasta.toFile()).redirectErrorStream(true).start();
        try (OutputStream out = processo.getOutputStream()) { out.write(entrada.getBytes(StandardCharsets.UTF_8)); }
        // Consome o pipe enquanto a JVM roda para não bloquear se o menu produzir muitas linhas.
        String saida = new String(processo.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        exigir(processo.waitFor(90, TimeUnit.SECONDS) && processo.exitValue() == 0, "Processo filho: " + saida);
        return saida;
    }
    private static void validar(GerenciadorIndices g) throws Exception {
        String r = g.validarTodos();
        exigir(r.contains("Validação global: OK") && !r.contains("ERRO"), r);
        System.out.println(r);
    }
    private static void exigir(boolean b, String motivo) { if (!b) throw new AssertionError(motivo); }
    @FunctionalInterface private interface Acao { void executar() throws Exception; }
    private static void falha(Acao acao) throws Exception {
        try { acao.executar(); } catch (IOException | IllegalArgumentException esperado) { return; }
        throw new AssertionError("Falha esperada não ocorreu");
    }
}
