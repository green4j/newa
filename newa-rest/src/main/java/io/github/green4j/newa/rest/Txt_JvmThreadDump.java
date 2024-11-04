package io.github.green4j.newa.rest;

import com.sun.management.ThreadMXBean;
import io.github.green4j.newa.text.LineFormatter;
import io.netty.handler.codec.http.FullHttpRequest;

import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.util.HashMap;
import java.util.Map;

import static io.github.green4j.newa.rest.Util.inSleep;
import static io.github.green4j.newa.rest.Util.toDuration;

public class Txt_JvmThreadDump implements TxtRestHandle {

    @Override
    public void doHandle(final FullHttpRequest request,
                         final PathParameters pathParameters,
                         final LineFormatter output) {

        final ThreadMXBean threadMXBean =
                (ThreadMXBean) ManagementFactory.getThreadMXBean(); // unchecked
        final ThreadInfo[] threadInfos = threadMXBean.dumpAllThreads(true, true);
        final Map<Long, String> threadDescriptions = new HashMap<>();

        output.append("threads [").append(Integer.toString(threadInfos.length)).append("]\n");

        for (int i = 0; i < threadInfos.length; i++) {
            final ThreadInfo threadInfo = threadInfos[i];

            final Thread.State ts = threadInfo.getThreadState();
            final StackTraceElement[] stack = threadInfo.getStackTrace();

            final StringBuilder sb = new StringBuilder("[")
                    .append(threadInfo.getThreadName()).append("] #")
                    .append(threadInfo.getThreadId())
                    .append(threadInfo.isDaemon() ? " daemon" : "")
                    .append(" prio=").append(threadInfo.getPriority())
                    .append(" cpu=").append(
                            toDuration(
                                    threadMXBean.getThreadCpuTime(
                                            threadInfo.getThreadId()) / (1000 * 1000)))
                    .append(" user=").append(
                            toDuration(
                                    threadMXBean.getThreadUserTime(
                                            threadInfo.getThreadId()) / (1000 * 1000)))
                    .append(" mem=").append(
                            threadMXBean.getThreadAllocatedBytes(threadInfo.getThreadId()))
                    .append(' ').append(threadInfo.getThreadState());
            if (threadInfo.getLockName() != null) {
                sb.append(" on ").append(threadInfo.getLockName());
            }
            if (threadInfo.getLockOwnerName() != null) {
                sb.append(" owned by \"").append(threadInfo.getLockOwnerName())
                        .append("\" id=").append(threadInfo.getLockOwnerId());
            }
            if (threadInfo.isSuspended()) {
                sb.append(" (suspended)");
            }
            if (threadInfo.isInNative()) {
                sb.append(" (in native)");
            }

            if (ts == Thread.State.TIMED_WAITING
                    && stack != null && stack.length > 0) {
                final StackTraceElement top = stack[0];
                if (inSleep(top)) {
                    sb.append(" (sleeping)");
                }
            }

            output.append("    ").append(sb);

            threadDescriptions.put(threadInfo.getThreadId(), sb.toString());

            switch (ts) {
                case BLOCKED:
                    output.append(" blockedCount: ");
                    output.append(Long.toString(threadInfo.getBlockedCount()));
                    break;
                case WAITING:
                case TIMED_WAITING:
                    output.append(" waitedCount: ");
                    output.append(Long.toString(threadInfo.getWaitedCount()));
                    break;
                default:
                    break;
            }

            output.append('\n');

            addStackTrace(output, threadInfo, sb);
        }

        final long[] dlt = threadMXBean.findDeadlockedThreads();
        output.append("deadlocks [").append(
                dlt != null ? Integer.toString(dlt.length) : "0"
        ).append("]\n");
        if (dlt != null && dlt.length > 0) {
            for (final long tid : dlt) {
                final String td = threadDescriptions.get(tid);
                if (td == null) {
                    output.append("    #").append(Long.toString(tid)).append('\n');
                    continue;
                }
                output.append("    ").append(td).append('\n');
            }
        }
    }

    private static void addStackTrace(final LineFormatter to,
                                      final ThreadInfo threadInfo,
                                      final StringBuilder sb) {
        final Thread.State ts = threadInfo.getThreadState();
        final LockInfo lock = threadInfo.getLockInfo();

        final StackTraceElement[] stackTrace = threadInfo.getStackTrace();

        to.append("        stack [").append(Integer.toString(stackTrace.length)).append("]\n");

        for (int i = 0; i < stackTrace.length; i++) {
            final StackTraceElement ste = stackTrace[i];
            sb.setLength(0);
            sb.append(ste.toString());

            if (i == 0) {
                switch (ts) {
                    case BLOCKED:
                        sb.append(" - blocked on ").append(lock);
                        break;
                    case WAITING:
                    case TIMED_WAITING:
                        if ("java.lang.Object".equals(ste.getClassName())
                                && "wait".equals(ste.getMethodName())) {
                            sb.append(" - waiting on ").append(lock);
                        } else {
                            if (inSleep(ste)) {
                                sb.append(" - sleeping");
                            } else {
                                sb.append(" - parking");
                                if (lock != null) {
                                    sb.append(" to wait on ").append(lock);
                                }
                            }
                        }
                        break;
                    default:
                        break;
                }
            }
            for (final MonitorInfo mi : threadInfo.getLockedMonitors()) {
                if (mi.getLockedStackDepth() == i) {
                    sb.append(" - locked ").append(mi);
                }
            }
            to.append("            ").append(sb).append('\n');
        }

        final LockInfo[] locks = threadInfo.getLockedSynchronizers();
        if (locks.length > 0) {
            to.append("        lockedSynchronizers [").append(Integer.toString(locks.length)).append("]\n");
            for (final LockInfo li : locks) {
                to.append("            ").append(li.toString()).append('\n');
            }
        }
    }
}
