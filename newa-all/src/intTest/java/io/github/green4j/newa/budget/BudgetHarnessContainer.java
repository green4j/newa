/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.budget;

import com.github.dockerjava.api.command.InspectContainerResponse;
import io.github.green4j.newa.budget.harness.BudgetServerHarness;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The harness in a container with a real memory ceiling: a stock JRE image, the module's own runtime
 * classpath copied in, and whatever {@code --memory}, {@code -Xmx} and {@code -XX:MaxDirectMemorySize} the
 * test wants to hold it to.
 *
 * <p>Started and stopped by hand from {@code @BeforeAll} and {@code @AfterAll} rather than by the
 * testcontainers extension: several of these tests expect the container to die, and one which owns its own
 * lifecycle can be asked about how it died.
 */
final class BudgetHarnessContainer extends GenericContainer<BudgetHarnessContainer> {
    /**
     * A JRE and nothing else. There is no image to build: what runs is the jars the build just made.
     */
    private static final DockerImageName IMAGE = DockerImageName.parse("eclipse-temurin:21-jre");

    private static final String LIB_PATH = "/app/lib";

    private static final HttpClient ADMIN = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final List<String> jvmOptions = new ArrayList<>();

    private long memoryLimitBytes = 256L * 1024 * 1024;

    BudgetHarnessContainer() {
        super(IMAGE);

        withCopyFileToContainer(MountableFile.forHostPath(harnessLib()), LIB_PATH);
        withExposedPorts(
                BudgetServerHarness.REST_PORT,
                BudgetServerHarness.FILE_PORT,
                BudgetServerHarness.WEBSOCKET_PORT,
                BudgetServerHarness.ADMIN_PORT
        );
        waitingFor(Wait.forLogMessage(".*" + BudgetServerHarness.READY_LINE + ".*\\n", 1)
                .withStartupTimeout(Duration.ofMinutes(2)));
        withStartupAttempts(1);

        // read when the container is created, so a limit set after the constructor still counts
        withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                .withMemory(memoryLimitBytes)
                .withMemorySwap(memoryLimitBytes)); // no swap: a container over its limit is killed, not paged

        // what the VM raises itself. Netty's direct-memory error is thrown from Java and never reaches
        // this flag, which is why the harness halts on that one by hand
        withJvmOption("-XX:+ExitOnOutOfMemoryError");
        withJvmOption("-Dio.netty.leakDetection.level=paranoid");
        withJvmOption("-Dio.netty.leakDetection.targetRecords=32");
    }

    /**
     * @param megabytes the cgroup limit of the container, which is what a JVM without {@code -Xmx} sizes
     *                  its heap from and what the kernel kills the process over
     * @return this container
     */
    BudgetHarnessContainer withMemoryLimitMb(final int megabytes) {
        memoryLimitBytes = megabytes * 1024L * 1024L;
        return this;
    }

    BudgetHarnessContainer withHeapMb(final int megabytes) {
        return withJvmOption("-Xmx" + megabytes + "m");
    }

    BudgetHarnessContainer withMaxRamPercentage(final int percentage) {
        return withJvmOption("-XX:MaxRAMPercentage=" + percentage + ".0");
    }

    BudgetHarnessContainer withDirectMemoryMb(final int megabytes) {
        return withJvmOption("-XX:MaxDirectMemorySize=" + megabytes + "m");
    }

    BudgetHarnessContainer withJvmOption(final String option) {
        jvmOptions.add(option);
        return this;
    }

    /**
     * Runs the same servers with no budget at all, which is the only way to say what the budget is worth.
     *
     * @return this container
     */
    BudgetHarnessContainer withoutBudget() {
        return withEnv("NEWA_BUDGET", "off");
    }

    @Override
    protected void configure() {
        final List<String> command = new ArrayList<>();
        command.add("java");
        command.addAll(jvmOptions);
        command.add("-cp");
        command.add(LIB_PATH + "/*"); // expanded by the JVM itself - there is no shell in between
        command.add(BudgetServerHarness.class.getName());
        withCommand(command.toArray(new String[0]));
    }

    /**
     * Waits for the admin port on top of the log line the wait strategy took: a bound port inside the
     * container is not yet a mapped one on this machine, and how long that takes is the Docker daemon's
     * business - a virtual machine on a developer's laptop, nothing at all on Linux.
     */
    @Override
    public void start() {
        super.start();

        final long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (true) {
            try {
                report();
                return;
            } catch (final IllegalStateException unreachable) {
                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException("The harness never answered on "
                            + getHost() + ':' + getMappedPort(BudgetServerHarness.ADMIN_PORT)
                            + ", its log says:\n" + getLogs(), unreachable);
                }
                sleep(200);
            }
        }
    }

    int restPort() {
        return getMappedPort(BudgetServerHarness.REST_PORT);
    }

    int filePort() {
        return getMappedPort(BudgetServerHarness.FILE_PORT);
    }

    int webSocketPort() {
        return getMappedPort(BudgetServerHarness.WEBSOCKET_PORT);
    }

    /**
     * @param path of the request, on the REST port
     * @return the response, however long the harness took to answer within reason
     * @throws IOException if the request could not be made
     * @throws InterruptedException if the wait for the response was interrupted
     */
    HttpResponse<String> get(final String path) throws IOException, InterruptedException {
        return ADMIN.send(
                HttpRequest.newBuilder(URI.create("http://" + getHost() + ':' + restPort() + path))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    /**
     * @return the harness's own view of the budget, taken over the admin port, which is outside the budget
     *         and so still answers when it is full
     */
    BudgetReport report() {
        try {
            final HttpResponse<String> response = ADMIN.send(
                    HttpRequest.newBuilder(URI.create("http://" + getHost() + ':'
                                    + getMappedPort(BudgetServerHarness.ADMIN_PORT)
                                    + BudgetServerHarness.ADMIN_PATH))
                            .timeout(Duration.ofSeconds(10))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() != 200) {
                throw new IllegalStateException("The harness answered " + response.statusCode()
                        + " on its admin port: " + response.body());
            }
            return new BudgetReport(response.body());
        } catch (final IOException failed) {
            throw new IllegalStateException("The harness admin port could not be reached", failed);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading the harness admin port", interrupted);
        }
    }

    /**
     * @param until what the report has to say
     * @param timeout to wait for it
     * @return the first report which satisfied the condition
     */
    BudgetReport awaitReport(final Predicate<BudgetReport> until,
                             final Duration timeout) {
        final long deadline = System.nanoTime() + timeout.toNanos();
        BudgetReport last = report();
        while (!until.test(last)) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("The harness never reported what was expected:\n" + last);
            }
            sleep(50);
            last = report();
        }
        return last;
    }

    boolean alive() {
        return Boolean.TRUE.equals(state().getRunning());
    }

    /**
     * @return whether the kernel killed the process for going over the container's memory limit
     */
    boolean oomKilled() {
        return Boolean.TRUE.equals(state().getOOMKilled());
    }

    /**
     * @return what the process exited with, or null while it is still running
     */
    Long exitCode() {
        return alive() ? null : state().getExitCodeLong();
    }

    /**
     * @return the most memory the container ever held, or -1 where the kernel does not say
     */
    long peakMemoryBytes() {
        final long peak = cgroupValue("/sys/fs/cgroup/memory.peak"); // cgroup v2
        return peak > 0 ? peak : cgroupValue("/sys/fs/cgroup/memory/memory.max_usage_in_bytes");
    }

    long memoryLimitBytes() {
        return memoryLimitBytes;
    }

    private long cgroupValue(final String path) {
        try {
            final Container.ExecResult read = execInContainer("cat", path);
            return read.getExitCode() == 0 ? Long.parseLong(read.getStdout().trim()) : -1L;
        } catch (final IOException | NumberFormatException | IllegalStateException unavailable) {
            return -1L;
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return -1L;
        }
    }

    private InspectContainerResponse.ContainerState state() {
        return getCurrentContainerInfo().getState();
    }

    private static Path harnessLib() {
        final String lib = System.getProperty("newa.harness.lib");
        if (lib == null) {
            throw new IllegalStateException(
                    "newa.harness.lib is not set: run these tests through the intTest task");
        }
        return Path.of(lib);
    }

    static void sleep(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting", interrupted);
        }
    }
}
