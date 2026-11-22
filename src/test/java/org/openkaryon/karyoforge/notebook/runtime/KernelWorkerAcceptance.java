package org.openkaryon.karyoforge.notebook.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Forked real-process regression: pipe readers must not occupy both Java 21 carriers. */
public final class KernelWorkerAcceptance {
    private KernelWorkerAcceptance() {}

    public static void main(String[] arguments) throws Exception {
        Path directory = Path.of(arguments[1]);
        for (int attempt = 0; attempt < 2; attempt++) {
            var kernel = new LocalNotebookKernel(Path.of(arguments[0]), directory);
            var completed = new CompletableFuture<LocalNotebookKernel.Execution>();
            Thread.ofVirtual().name("acceptance-kernel-worker").start(() -> {
                try {
                    kernel.start();
                    completed.complete(kernel.execute("import sys\nprint('stdout-ready')\nprint('stderr-ready', file=sys.stderr)\n6 * 7"));
                } catch (Exception failure) { completed.completeExceptionally(failure); }
            });
            try {
                var result = completed.get(30, TimeUnit.SECONDS);
                String output = result.outputs().toString();
                if (!"ok".equals(result.status()) || !Integer.valueOf(1).equals(result.count())
                        || !output.contains("stdout-ready") || !output.contains("stderr-ready") || !output.contains("42"))
                    throw new AssertionError("The virtual worker did not receive the real kernel result: " + result);
            } finally {
                kernel.close();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
                while (kernel.hasOwnedProcesses() && System.nanoTime() < deadline) Thread.sleep(25);
                if (kernel.hasOwnedProcesses()) throw new AssertionError("The owned kernel did not stop on two carriers");
                Path runtime = kernel.runtimeDirectory();
                while (runtime != null && Files.exists(runtime) && System.nanoTime() < deadline) Thread.sleep(25);
                if (runtime != null && Files.exists(runtime)) throw new AssertionError("The owned runtime was not removed");
            }
        }
        System.out.println("TWO_CARRIER_KERNEL_ACCEPTANCE_PASSED");
    }
}
