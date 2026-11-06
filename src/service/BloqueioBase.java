package service;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/** Lock lateral sobrevive à troca de dados.db; todas as instâncias Main cooperam.
 * Reentrante somente na mesma thread, permitindo Main chamar os serviços TP3.
 */
public final class BloqueioBase implements AutoCloseable {
    private static final Map<Path, Estado> ATIVOS = new HashMap<>();
    private record Estado(Thread dono, FileChannel canal, FileLock lock, int referencias) {}
    private final Path chave;
    private boolean fechado;
    private BloqueioBase(Path chave) { this.chave = chave; }

    public static synchronized BloqueioBase adquirir(Path db) throws IOException {
        Path caminho = db.toAbsolutePath().normalize();
        Files.createDirectories(caminho.getParent());
        if (Files.exists(caminho)) caminho = caminho.toRealPath();
        else caminho = caminho.getParent().toRealPath().resolve(caminho.getFileName());
        Path chave = caminho.resolveSibling("." + caminho.getFileName() + ".tp3.lock");
        Estado e = ATIVOS.get(chave);
        if (e != null) {
            if (e.dono != Thread.currentThread()) throw new IOException("Base em uso por outra operação.");
            ATIVOS.put(chave, new Estado(e.dono, e.canal, e.lock, e.referencias + 1));
            return new BloqueioBase(chave);
        }
        FileChannel canal = FileChannel.open(chave, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = canal.tryLock();
            if (lock == null) throw new IOException("Base em uso por outra aplicação.");
            ATIVOS.put(chave, new Estado(Thread.currentThread(), canal, lock, 1));
            return new BloqueioBase(chave);
        } catch (IOException | OverlappingFileLockException ex) {
            canal.close(); throw new IOException("Não foi possível obter acesso exclusivo à base.", ex);
        }
    }
    @Override public void close() throws IOException {
        synchronized (BloqueioBase.class) {
            if (fechado) return;
            Estado e = ATIVOS.get(chave);
            if (e == null || e.dono != Thread.currentThread()) throw new IOException("Lock pertence a outra thread.");
            fechado = true;
            if (e.referencias > 1) ATIVOS.put(chave, new Estado(e.dono, e.canal, e.lock, e.referencias - 1));
            else {
                ATIVOS.remove(chave);
                try { e.lock.release(); } finally { e.canal.close(); }
            }
        }
    }
}
