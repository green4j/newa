package io.github.green4j.newa.rest;

import com.sun.management.OperatingSystemMXBean;
import io.github.green4j.newa.text.LineAppendable;
import io.netty.handler.codec.http.FullHttpRequest;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.util.Comparator;
import java.util.List;

import static io.github.green4j.newa.rest.Json_JvmInfo.getCpuLoad;
import static io.github.green4j.newa.rest.Json_JvmInfo.getFreeMemorySize;
import static io.github.green4j.newa.rest.Json_JvmInfo.getTotalMemorySize;
import static io.github.green4j.newa.rest.Util.formatUtcToIso8601;
import static io.github.green4j.newa.rest.Util.toDuration;
import static io.github.green4j.newa.rest.Util.toMemorySize;

public class Txt_JvmInfo implements TxtRestHandle {

    @Override
    public void doHandle(final FullHttpRequest request,
                         final PathParameters pathParameters,
                         final LineAppendable output) {
        final RuntimeMXBean runtimeMXBean =
                ManagementFactory.getRuntimeMXBean();
        final MemoryMXBean memoryMXBean =
                ManagementFactory.getMemoryMXBean();
        final OperatingSystemMXBean operatingSystemMXBean =
                (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean(); // unchecked
        final List<GarbageCollectorMXBean> garbageCollectorMXBeans =
                ManagementFactory.getGarbageCollectorMXBeans();

        output.append("jvm: ");
        output.append(runtimeMXBean.getVmName());
        output.append("\nversion: ");
        output.append(runtimeMXBean.getVmVersion());
        output.append("\nvendor: ");
        output.append(runtimeMXBean.getVmVendor());
        output.append("\npid: ");
        output.append(Long.toString(runtimeMXBean.getPid()));
        output.append("\nstartedAt: ");
        output.append(formatUtcToIso8601(runtimeMXBean.getStartTime()));
        output.append("\nuptime: ");
        output.append(toDuration(runtimeMXBean.getUptime()));

        output.append("os");
        output.append("\n    name: ");
        output.append(operatingSystemMXBean.getName());
        output.append(" \n   version: ");
        output.append(operatingSystemMXBean.getVersion());
        output.append("\n    arch: ");
        output.append(operatingSystemMXBean.getArch());

        final MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
        final MemoryUsage nonHeapUsage = memoryMXBean.getNonHeapMemoryUsage();

        output.append("\ncpu");
        dumpCpuInfo(output, operatingSystemMXBean);

        output.append("\nmemory");
        dumpMemoryInfo(output, operatingSystemMXBean, heapUsage, nonHeapUsage);

        output.append("\ngc");
        dumpGcInfo(output, garbageCollectorMXBeans);
    }

    private static void dumpCpuInfo(final LineAppendable output,
                                    final OperatingSystemMXBean operatingSystemMXBean) {
        output.append("\n    number: ");
        output.append(Integer.toString(operatingSystemMXBean.getAvailableProcessors()));
        output.append("\n    process: ");
        output.append(String.format("%.1f%%", operatingSystemMXBean.getProcessCpuLoad()));
        output.append("\n    system: ");
        output.append(String.format("\n%.1f%%", getCpuLoad(operatingSystemMXBean)));
    }

    private static void dumpMemoryInfo(final LineAppendable output,
                                       final OperatingSystemMXBean operatingSystemMXBean,
                                       final MemoryUsage heapUsage,
                                       final MemoryUsage nonHeapUsage) {
        output.append("\n    physical");
        output.append("\n        free: ");
        output.append(toMemorySize(getFreeMemorySize(operatingSystemMXBean)));
        output.append("\n        total: ");
        output.append(toMemorySize(getTotalMemorySize(operatingSystemMXBean)));

        output.append("\n    heap");
        output.append("\n        init: ");
        output.append(toMemorySize(heapUsage.getInit()));
        output.append("\n        used: ");
        output.append(toMemorySize(heapUsage.getUsed()));
        output.append("\n        committed: ");
        output.append(toMemorySize(heapUsage.getCommitted()));
        output.append("\n        max: ");
        output.append(toMemorySize(heapUsage.getMax()));

        output.append("\n    nonHeap");
        output.append("\n        init: ");
        output.append(toMemorySize(nonHeapUsage.getInit()));
        output.append("\n        used: ");
        output.append(toMemorySize(nonHeapUsage.getUsed()));
        output.append("\n        committed: ");
        output.append(toMemorySize(nonHeapUsage.getCommitted()));
    }

    private static void dumpGcInfo(final LineAppendable output,
                                   final List<GarbageCollectorMXBean> garbageCollectorMXBeans) {
        output.append("    collectors [")
                .append(Integer.toString(garbageCollectorMXBeans.size())).append("]\n");
        garbageCollectorMXBeans.stream()
                .sorted(Comparator.comparing(GarbageCollectorMXBean::getName))
                .forEach(
                        gc -> {
                            output.append("        name: ");
                            output.append(gc.getName());
                            output.append("\n        count: ");
                            output.append(Long.toString(gc.getCollectionCount()));
                            output.append("\n        time: ");
                            output.append(toDuration(gc.getCollectionTime()));
                            output.append('\n');
                        }
                );
    }
}
