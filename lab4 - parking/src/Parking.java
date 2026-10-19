
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

public class Parking {

    static final int PERMITS = 5;
    static final int TASKS = 12;
    static final int ROUNDS = 50;

    static final Semaphore permits = new Semaphore(PERMITS);

    static final AtomicInteger inService = new AtomicInteger();
    static final AtomicInteger peak = new AtomicInteger();

    // ЧАСТИНА А: обмежувач

    static String work(String path) {
        peak.accumulateAndGet(inService.incrementAndGet(), Math::max);

        try {
            Thread.sleep(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        inService.decrementAndGet();

        if (path.startsWith("/зламаний")) {
            throw new IllegalStateException(path + " відповів 503");
        }

        return path + " готово";
    }

    static String enter(String path) throws InterruptedException {
        permits.acquire();

        try {
            return work(path);
        } finally {
            permits.release();
        }
    }

    // Контрприклад: не виправляти

    static String leaky(String path) throws InterruptedException {
        permits.acquire();
        String body = work(path);
        permits.release();
        return body;
    }

    // ЧАСТИНА Б: два замки

    static final class Gate {}
    static final class Till {}

    static final Gate GATE = new Gate();
    static final Till TILL = new Till();

    static int parked;
    static int paid;

    static void park() {
        synchronized (GATE) {
            hold();

            synchronized (TILL) {
                parked++;
            }
        }
    }

    static void leave() {
        synchronized (GATE) {
            hold();

            synchronized (TILL) {
                paid++;
            }
        }
    }

    static void hold() {
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Звіт

    public static void main(String[] args) throws InterruptedException {
        System.out.println("ЧАСТИНА А. " + TASKS
                + " задач по ≈250 мс, дозволів " + PERMITS + ".");

        peak.set(0);

        int done = batch(Parking::enter, "/склад/", TASKS);

        System.out.println("  виконано " + done + " з " + TASKS
                + ", пік одночасних у сервісі " + peak.get()
                + ", вільних дозволів у кінці "
                + permits.availablePermits());

        System.out.println("\nКонтрприклад leaky(): release стоїть після роботи, а не у finally.");

        batch(Parking::leaky, "/зламаний/", PERMITS);

        System.out.println("  після " + PERMITS
                + " виключень вільних дозволів "
                + permits.availablePermits() + " з " + PERMITS);

        permits.release(PERMITS - permits.availablePermits());

        System.out.println("\nЧАСТИНА Б. " + ROUNDS
                + " раундів park() і leave() у два потоки.");

        System.out.println("Якщо вивід обірвався тут, ви бачите дедлок. Знімайте дамп зараз,");
        System.out.println("у другому вікні: jps, далі jstack <pid>. Є 20 секунд.");

        parked = 0;
        paid = 0;

        Thread a = daemon("park", () -> {
            for (int i = 0; i < ROUNDS; i++) {
                park();
            }
        });

        Thread b = daemon("leave", () -> {
            for (int i = 0; i < ROUNDS; i++) {
                leave();
            }
        });

        long t0 = System.nanoTime();

        a.start();
        b.start();

        long deadline = System.currentTimeMillis() + 20_000;

        a.join(Math.max(1, deadline - System.currentTimeMillis()));
        b.join(Math.max(1, deadline - System.currentTimeMillis()));

        boolean ok = !a.isAlive() && !b.isAlive();

        System.out.printf(
                "  %s за %.2f с: заїхало %d з %d, заплатило %d з %d%n",
                ok ? "завершилися" : "НЕ завершилися",
                (System.nanoTime() - t0) / 1e9,
                parked, ROUNDS, paid, ROUNDS
        );
    }

    static int batch(Task task, String prefix, int n)
            throws InterruptedException {

        AtomicInteger ok = new AtomicInteger();
        Thread[] w = new Thread[n];

        for (int i = 0; i < n; i++) {
            final String path = prefix + (i + 1);

            w[i] = new Thread(() -> {
                try {
                    if (task.run(path) != null) {
                        ok.incrementAndGet();
                    }
                } catch (Exception ignored) {
                }
            });
        }

        for (Thread x : w) {
            x.start();
        }

        for (Thread x : w) {
            x.join();
        }

        return ok.get();
    }

    interface Task {
        String run(String path) throws InterruptedException;
    }

    static Thread daemon(String name, Runnable body) {
        Thread t = new Thread(body, name);
        t.setDaemon(true);
        return t;
    }
}
