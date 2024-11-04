package io.github.green4j.newa.rest;

import com.sun.management.OperatingSystemMXBean;
import io.github.green4j.jelly.JsonGenerator;
import io.netty.handler.codec.http.FullHttpRequest;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryManagerMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.util.Comparator;
import java.util.List;

import static io.github.green4j.newa.rest.Util.formatUtcToIso8601;
import static io.github.green4j.newa.rest.Util.toDuration;
import static io.github.green4j.newa.rest.Util.toMemorySize;

public class Json_JvmInfo implements JsonRestHandle {
    private static final MethodHandle CPU_LOAD_GETTER;
    private static final MethodHandle TOTAL_MEMORY_SIZE_GETTER;
    private static final MethodHandle FREE_MEMORY_SIZE_GETTER;

    static {
        // To prevent using deprecated methods
        try {
            final MethodHandles.Lookup lookup = MethodHandles.lookup();
            final MethodType doubleGetterMethodType = MethodType.methodType(double.class);
            final MethodType longGetterMethodType = MethodType.methodType(long.class);

            MethodHandle cpuLoad;
            try {
                cpuLoad = lookup.findVirtual(OperatingSystemMXBean.class,
                        "getCpuLoad", doubleGetterMethodType);
            } catch (final NoSuchMethodException e) {
                cpuLoad = lookup.findVirtual(OperatingSystemMXBean.class,
                        "getSystemCpuLoad", doubleGetterMethodType);
            }

            MethodHandle totalMemorySize;
            try {
                totalMemorySize = lookup.findVirtual(OperatingSystemMXBean.class,
                        "getTotalMemorySize", longGetterMethodType);
            } catch (final NoSuchMethodException e) {
                totalMemorySize = lookup.findVirtual(OperatingSystemMXBean.class,
                        "getTotalPhysicalMemorySize", longGetterMethodType);
            }

            MethodHandle freeMemorySize;
            try {
                freeMemorySize = lookup.findVirtual(OperatingSystemMXBean.class,
                        "getFreeMemorySize", longGetterMethodType);
            } catch (final NoSuchMethodException e) {
                freeMemorySize = lookup.findVirtual(OperatingSystemMXBean.class,
                        "getFreePhysicalMemorySize", longGetterMethodType);
            }

            CPU_LOAD_GETTER = cpuLoad;
            TOTAL_MEMORY_SIZE_GETTER = totalMemorySize;
            FREE_MEMORY_SIZE_GETTER = freeMemorySize;
        } catch (final Exception e) {
            throw new Error(e);
        }
    }

    static double getCpuLoad(final com.sun.management.OperatingSystemMXBean mxBean) {
        try {
            return (double) CPU_LOAD_GETTER.invoke(mxBean);
        } catch (final Error | RuntimeException e) {
            throw e;
        } catch (final Throwable t) {
            throw new RuntimeException(t);
        }
    }

    static long getTotalMemorySize(final com.sun.management.OperatingSystemMXBean mxBean) {
        try {
            return (long) TOTAL_MEMORY_SIZE_GETTER.invoke(mxBean);
        } catch (final Error | RuntimeException e) {
            throw e;
        } catch (final Throwable t) {
            throw new RuntimeException(t);
        }
    }

    static long getFreeMemorySize(final com.sun.management.OperatingSystemMXBean mxBean) {
        try {
            return (long) FREE_MEMORY_SIZE_GETTER.invoke(mxBean);
        } catch (final Error | RuntimeException e) {
            throw e;
        } catch (final Throwable t) {
            throw new RuntimeException(t);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void doHandle(final FullHttpRequest request,
                         final PathParameters pathParameters,
                         final JsonGenerator output) {
        final RuntimeMXBean runtimeMXBean =
                ManagementFactory.getRuntimeMXBean();
        final MemoryMXBean memoryMXBean =
                ManagementFactory.getMemoryMXBean();
        final OperatingSystemMXBean operatingSystemMXBean =
                (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean(); // unchecked
        final List<GarbageCollectorMXBean> garbageCollectorMXBeans =
                ManagementFactory.getGarbageCollectorMXBeans();

        output.startObject();

        output.objectMember("jvm");
        output.stringValue(runtimeMXBean.getVmName(), true);
        output.objectMember("version");
        output.stringValue(runtimeMXBean.getVmVersion(), true);
        output.objectMember("vendor");
        output.stringValue(runtimeMXBean.getVmVendor(), true);
        output.objectMember("pid");
        output.numberValue(runtimeMXBean.getPid());
        output.objectMember("startedAt");
        output.stringValue(formatUtcToIso8601(runtimeMXBean.getStartTime()));
        output.objectMember("uptime");
        output.stringValue(toDuration(runtimeMXBean.getUptime()));

        output.objectMember("os");
        output.startObject(); // os
        output.objectMember("name");
        output.stringValue(operatingSystemMXBean.getName(), true);
        output.objectMember("version");
        output.stringValue(operatingSystemMXBean.getVersion(), true);
        output.objectMember("arch");
        output.stringValue(operatingSystemMXBean.getArch(), true);
        output.endObject(); // os

        final MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
        final MemoryUsage nonHeapUsage = memoryMXBean.getNonHeapMemoryUsage();

        output.objectMember("cpu");
        dumpCpuObject(output, operatingSystemMXBean);

        output.objectMember("memory");
        dumpMemoryObject(output, operatingSystemMXBean, heapUsage, nonHeapUsage);

        output.objectMember("gc");
        dumpGcInfoObject(output, garbageCollectorMXBeans);

        output.endObject();
    }

    private static void dumpCpuObject(final JsonGenerator output,
                                      final OperatingSystemMXBean operatingSystemMXBean) {
        output.startObject(); // cpu
        output.objectMember("number");
        output.numberValue(operatingSystemMXBean.getAvailableProcessors());
        output.objectMember("process");
        output.stringValue(String.format("%.1f%%", operatingSystemMXBean.getProcessCpuLoad()));
        output.objectMember("system");
        output.stringValue(String.format("%.1f%%", getCpuLoad(operatingSystemMXBean)));
        output.endObject(); // cpu
    }

    private static void dumpMemoryObject(final JsonGenerator output,
                                         final OperatingSystemMXBean operatingSystemMXBean,
                                         final MemoryUsage heapUsage,
                                         final MemoryUsage nonHeapUsage) {
        output.startObject(); // memory

        output.objectMember("physical");
        output.startObject();
        output.objectMember("free");
        output.stringValue(toMemorySize(getFreeMemorySize(operatingSystemMXBean)));
        output.objectMember("total");
        output.stringValue(toMemorySize(getTotalMemorySize(operatingSystemMXBean)));
        output.endObject(); // memory

        output.objectMember("heap");
        output.startObject(); // heap
        output.objectMember("init");
        output.stringValue(toMemorySize(heapUsage.getInit()));
        output.objectMember("used");
        output.stringValue(toMemorySize(heapUsage.getUsed()));
        output.objectMember("committed");
        output.stringValue(toMemorySize(heapUsage.getCommitted()));
        output.objectMember("max");
        output.stringValue(toMemorySize(heapUsage.getMax()));
        output.endObject(); // heap

        output.objectMember("nonHeap");
        output.startObject(); // nonHeap
        output.objectMember("init");
        output.stringValue(toMemorySize(nonHeapUsage.getInit()));
        output.objectMember("used");
        output.stringValue(toMemorySize(nonHeapUsage.getUsed()));
        output.objectMember("committed");
        output.stringValue(toMemorySize(nonHeapUsage.getCommitted()));
        output.endObject(); // nonHeap

        output.endObject();
    }

    private static void dumpGcInfoObject(final JsonGenerator output,
                                         final List<GarbageCollectorMXBean> garbageCollectorMXBeans) {
        output.startObject(); // gc
        output.objectMember("collectors");
        output.startArray();
        garbageCollectorMXBeans.stream()
                .sorted(Comparator.comparing(MemoryManagerMXBean::getName))
                .forEach(
                        gc -> {
                            output.startObject();
                            output.objectMember("name");
                            output.stringValue(gc.getName());
                            output.objectMember("count");
                            output.numberValue(gc.getCollectionCount());
                            output.objectMember("time");
                            output.stringValue(toDuration(gc.getCollectionTime()));
                            output.endObject();
                        }
                );
        output.endArray();
        output.endObject(); // gc
    }
}
