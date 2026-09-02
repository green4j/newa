/*
 * MIT License
 *
 * Copyright (c) 2023-2026 Anatoly Gudkov and others
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.github.green4j.newa.rest.handles;

import com.sun.management.OperatingSystemMXBean;
import io.github.green4j.newa.rest.RestContext;
import io.github.green4j.newa.rest.TxtRestHandle;
import io.github.green4j.newa.text.LineAppendable;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.util.Comparator;
import java.util.List;

import static io.github.green4j.newa.rest.handles.JsonJvmInfo.getCpuLoad;
import static io.github.green4j.newa.rest.handles.JsonJvmInfo.getFreeMemorySize;
import static io.github.green4j.newa.rest.handles.JsonJvmInfo.getTotalMemorySize;
import static io.github.green4j.newa.rest.handles.Util.formatUtcToIso8601;
import static io.github.green4j.newa.rest.handles.Util.toDuration;
import static io.github.green4j.newa.rest.handles.Util.toMemorySize;

public class TxtJvmInfo implements TxtRestHandle {

    @Override
    public void doHandle(final RestContext context,
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
        output.appendln(runtimeMXBean.getVmName());
        output.append("version: ");
        output.appendln(runtimeMXBean.getVmVersion());
        output.append("vendor: ");
        output.appendln(runtimeMXBean.getVmVendor());
        output.append("pid: ");
        output.appendln(Long.toString(runtimeMXBean.getPid()));
        output.append("startedAt: ");
        output.append(formatUtcToIso8601(runtimeMXBean.getStartTime()));
        output.append("uptime: ");
        output.appendln(toDuration(runtimeMXBean.getUptime()));

        output.appendln("os");
        output.tab(1).append("name: ");
        output.appendln(operatingSystemMXBean.getName());
        output.tab(1).append("version: ");
        output.appendln(operatingSystemMXBean.getVersion());
        output.tab(1).append("arch: ");
        output.appendln(operatingSystemMXBean.getArch());

        final MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
        final MemoryUsage nonHeapUsage = memoryMXBean.getNonHeapMemoryUsage();

        output.appendln("cpu");
        dumpCpuInfo(output, operatingSystemMXBean);

        output.appendln("memory");
        dumpMemoryInfo(output, operatingSystemMXBean, heapUsage, nonHeapUsage);

        output.appendln("gc");
        dumpGcInfo(output, garbageCollectorMXBeans);
    }

    private static void dumpCpuInfo(final LineAppendable output,
                                    final OperatingSystemMXBean operatingSystemMXBean) {
        output.tab(1).append("number: ");
        output.appendln(Integer.toString(operatingSystemMXBean.getAvailableProcessors()));
        output.tab(1).append("process: ");
        output.appendln(String.format("%.1f%%", operatingSystemMXBean.getProcessCpuLoad()));
        output.tab(1).append("system: ");
        output.appendln(String.format("%.1f%%", getCpuLoad(operatingSystemMXBean)));
    }

    private static void dumpMemoryInfo(final LineAppendable output,
                                       final OperatingSystemMXBean operatingSystemMXBean,
                                       final MemoryUsage heapUsage,
                                       final MemoryUsage nonHeapUsage) {
        output.tab(1).appendln("physical");
        output.tab(2).append("free: ");
        output.appendln(toMemorySize(getFreeMemorySize(operatingSystemMXBean)));
        output.tab(2).append("total: ");
        output.appendln(toMemorySize(getTotalMemorySize(operatingSystemMXBean)));

        output.tab(1).appendln("heap");
        output.tab(2).append("init: ");
        output.appendln(toMemorySize(heapUsage.getInit()));
        output.tab(2).append("used: ");
        output.appendln(toMemorySize(heapUsage.getUsed()));
        output.tab(2).append("committed: ");
        output.appendln(toMemorySize(heapUsage.getCommitted()));
        output.tab(2).append("max: ");
        output.appendln(toMemorySize(heapUsage.getMax()));

        output.tab(1).appendln("nonHeap");
        output.tab(2).append("init: ");
        output.appendln(toMemorySize(nonHeapUsage.getInit()));
        output.tab(2).append("used: ");
        output.appendln(toMemorySize(nonHeapUsage.getUsed()));
        output.tab(2).append("committed: ");
        output.appendln(toMemorySize(nonHeapUsage.getCommitted()));
    }

    private static void dumpGcInfo(final LineAppendable output,
                                   final List<GarbageCollectorMXBean> garbageCollectorMXBeans) {
        output.tab(1).append("collectors [")
                .append(Integer.toString(garbageCollectorMXBeans.size())).append("]\n");
        garbageCollectorMXBeans.stream()
                .sorted(Comparator.comparing(GarbageCollectorMXBean::getName))
                .forEach(
                        gc -> {
                            output.tab(2).append("name: ");
                            output.appendln(gc.getName());
                            output.tab(2).append("count: ");
                            output.appendln(Long.toString(gc.getCollectionCount()));
                            output.tab(2).append("time: ");
                            output.appendln(toDuration(gc.getCollectionTime()));
                        }
                );
    }
}
