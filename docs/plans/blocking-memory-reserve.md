# Plan: a blocking reservation in `ServerMemoryBudget`

## Motivation

`ServerMemoryBudget.tryReserve(heap, direct)` answers at once. That suits a REST handle, which turns a
refusal into `503`, but not a producer which ought to wait for room: a batch loader admitting downloads,
a worker pool admitting jobs. Today such a caller has to poll `tryReserve` with a backoff of its own.

## API

The method family follows the rule the class already has, and the JDK has for `Semaphore.tryAcquire` and
`Lock.tryLock`: a `try` form answers `null` whenever it does not reserve; the form without `try` never comes
back empty-handed. `Registration.tryAcquire()` and `tryReserve(heap, direct)` both return `null` today, so the
timed form, which also may return `null`, is a `tryReserve` overload:

```java
/**
 * Waits until both capacities have room for the reservation.
 *
 * @throws IllegalArgumentException when a size is negative or exceeds its capacity, which no wait can fit
 * @throws InterruptedException when interrupted before or while waiting; nothing is reserved then
 */
public Lease reserve(long heapBytes, long directMemoryBytes) throws InterruptedException;

/**
 * Waits at most the timeout until both capacities have room for the reservation.
 *
 * @return the lease, or null when the timeout elapsed first or a size exceeds its capacity
 * @throws IllegalArgumentException when a size is negative
 * @throws InterruptedException when interrupted before or while waiting; nothing is reserved then
 */
public Lease tryReserve(long heapBytes, long directMemoryBytes, long timeout, TimeUnit unit)
        throws InterruptedException;
```

Decisions inside that family:

- **Oversized timed request: `null` at once.** It is the answer `tryReserve(heap, direct)` gives for the same
  sizes, and no wait could change it, so the call does not wait out its timeout. Rejected: throwing
  `IllegalArgumentException` as `reserve` does - two `tryReserve` overloads would then disagree on one input.
  `reserve` throws only because it has no `null` to answer with.
- **A timeout of zero or less** makes the timed form answer as `tryReserve(heap, direct)` does, apart from the
  interrupt check below.
- **An interrupt on entry throws** even when there is room, as `Semaphore.acquire` and
  `Semaphore.tryAcquire(timeout)` do: a cancelled job (`Future.cancel(true)`) must not take memory on its way
  out. Checking only inside `wait()` would let it.
- Rejected: `reserve(heap, direct, timeout, unit)` for the timed form. In this class, and in the JDK, a method
  without `try` which may return `null` would be the one exception to the naming.

`tryReserve(heap, direct)` keeps its contract unchanged.

A refusal is `null`, as it is today in `Registration.tryAcquire()` (the admission in
`NettyServerBuilder.limited` closes the channel on `null`) and in `tryReserve`. A no-op lease is handed out
only by `RestContext.reserveMemory` on an unbudgeted server; it cannot be told apart from a granted lease, so
returning it for "no room" would let the caller run the work unaccounted. A budget never returns it from any
of its methods, and the new two do not either.

### `Lease.EMPTY` becomes `Lease.NO_BUDGET`

The constant's javadoc already says what it is for - "what code which reserves memory is given where there
is no budget to reserve from" - and its only producer is that branch of `reserveMemory`. `EMPTY` names what
it holds, which a refusal holds too, so `return Lease.EMPTY` on a "no room" path reads as plausible;
`return Lease.NO_BUDGET` there reads as the bug it is. The name now carries the one rule the javadoc had to
state.

- No deprecated alias. It shipped in 0.5.1, newa is 0.x and has broken API between minors for the sake of the
  form; one name per concept. A caller of `Lease.EMPTY` learns of the rename from the compiler.
- Rejected: `UNBUDGETED` - the same meaning, but `NO_BUDGET` is the phrase `reserveMemory`'s javadoc and
  `docs/memory-budget.md` already use ("a server without a budget").
- The value is unchanged: a shared lease whose close does nothing. Application code which wants a lease for
  work it chose not to budget may still use it, and the name still reads right there.

### Heap and direct memory: separately and together, atomically

One call takes both sizes, as `tryReserve(heap, direct)` does; there is no per-kind method.

- **Separately:** a zero size never waits for its kind. `fits` compares `0 <= capacity - reserved`, which holds
  by the class invariant (reservations never exceed capacity; checked against `register`, `acquire`,
  `release` after `close`, and `close` with a partly used floor). So `reserve(heap, 0)` does not wait while
  the direct capacity is full, and `reserve(0, direct)` does not wait while the heap is full.
- **Together:** the check and the commit of both sizes are one critical section under the monitor of `this`,
  the same one `acquire` and `tryReserve` use. A waiter holds nothing while it waits: it never takes the heap
  and then waits for direct memory, so two waiters cannot each hold the half the other needs, and a waiter
  does not shrink what connections can have. It returns only when both fit at the same check; freeing one
  kind wakes it, it re-checks, and it waits again if the other kind is still short.
- The cost of that atomicity is that a waiter for both is starved more easily than one for a single kind:
  the two kinds must be free at the same moment. That is the barging note below, not a separate problem.

## Changes

1. `ServerMemoryBudget`
   - Add `reserve(heap, direct)` and `tryReserve(heap, direct, timeout, unit)`. The waiting loop runs on the
     existing monitor of `this`: `while (!fits(heap, direct)) wait()` /
     `TimeUnit.NANOSECONDS.timedWait(this, remaining)` against a `System.nanoTime()` deadline, after a
     `Thread.interrupted()` check on entry. Arguments are checked before the lock is taken.
   - Extract `fits(heap, direct)` and the commit `add(heap, direct)`, and use both from `tryReserve` too, so
     the immediate and the waiting forms cannot drift apart. `acquire` keeps its own check, which also names
     the `RefusalReason`.
   - Call `notifyAll()` on every path which lowers a reservation, inside the lock:
     `unreserve` (a lease from any reservation closed), `release` (a connection closed) and
     `close(Registration)` (an unused floor returned). `release` frees nothing while a server is within
     its floor; a wake-up there is harmless and keeps the code unconditional. Rejected: a waiter count to
     skip `notifyAll()` - with no waiters it is already a check of an empty wait set under a lock which is
     held anyway.
   - Reject an oversized `reserve` with `IllegalArgumentException` before waiting. Clamping it to the
     capacity would silently break the invariant that reservations never exceed capacity; a caller which
     wants "run alone" clamps itself with `snapshot().heapCapacityBytes()`.
   - Rename `Lease.EMPTY` to `Lease.NO_BUDGET`; its javadoc keeps the sentence on when it is given.
2. `RestContext.reserveMemory`: returns `Lease.NO_BUDGET`, in the code and in its `@return`.
3. Javadoc (it ships and the README does not, so each piece stands alone):
   - `reserve` and the timed `tryReserve`: no fairness - connection admissions and `tryReserve` may take freed
     bytes ahead of a waiter, so under sustained load a large reservation may wait indefinitely, and the
     timeout bounds the wait without guaranteeing progress. A size within capacity but above what floors of
     open registrations and never-closed leases leave also waits for good: only the capacity is checked.
   - `reserve` and the timed `tryReserve`: never call them on a Netty event loop. Connections are released on
     event loops, so a blocked loop stalls the very releases a waiter needs, and every channel of that loop
     with them. A REST handle reserves with `RestContext.reserveMemory` instead.
   - The places which name `tryReserve(long, long)` as the only application reservation now speak of
     reservations in general: the class head, `Observer` (a reservation is not reported, waiting or not),
     `Snapshot` (open reservations) and `Lease` (taken by any of the three methods).
4. `docs/memory-budget.md`
   - "Application-owned memory": a paragraph on `reserve` / timed `tryReserve` versus `tryReserve`, the
     event-loop rule, and the barging note; `Lease.EMPTY` becomes `Lease.NO_BUDGET`.
   - "Guarantee and known limits": "open `tryReserve` leases" becomes "open reservations".

`README.md` does not mention application reservations or `Lease`, so it does not change: the budget's one
home in the docs is `docs/memory-budget.md`. The repository keeps no changelog and the release workflow writes
no release notes.

## Tests (`ServerMemoryBudgetTest`)

Waiting is observed by polling the waiter's `Thread.getState()` until `WAITING`/`TIMED_WAITING`, not by
sleeping; the timed tests use timeouts of tens of milliseconds. Where cases differ only in sizes (which kind
is full, which kind is asked for) they are one parameterized table.

- `reserve` returns at once when there is room, and the reservation shows in `snapshot()`.
- `reserve` blocks while the capacity is taken and returns once that lease is closed.
- `reserve` wakes when a connection is released (`Registration.tryAcquire()` lease closed).
- `reserve` wakes when a registration with an unused floor is closed.
- Separately: `reserve(heap, 0)` returns at once while the direct capacity is full, and `reserve(0, direct)`
  while the heap is full; the same for the timed `tryReserve` with a zero timeout.
- Together: with the heap free and the direct capacity full, `reserve(heap, direct)` waits, `snapshot()`
  shows no heap taken by it, and `tryReserve(heap, 0)` from another thread succeeds meanwhile. Closing a heap
  lease alone does not release a waiter which still lacks direct memory; closing the direct lease does, and
  both sizes appear in `snapshot()` together.
- Two waiters, each needing the heap and direct memory which only one of them can have, end with one lease
  and one waiter, never with each holding half.
- The timed `tryReserve` returns `null` after its timeout and reserves nothing (`assertNull`, which also
  rules out `Lease.NO_BUDGET`).
- The timed `tryReserve` returns `null` at once for a size above either capacity, without waiting its timeout.
- An interrupted waiter throws `InterruptedException`, keeps nothing reserved, and a later `reserve` fits.
- A thread interrupted before the call throws from `reserve` and the timed `tryReserve` although there is
  room, and reserves nothing.
- An oversized `reserve` throws `IllegalArgumentException` for either capacity; zero sizes always fit.
- Negative sizes throw `IllegalArgumentException` from all three methods.
- `tryReserve(heap, direct)` is unchanged for an oversized request (`null`).

In `ReserveMemoryTest` (newa-rest), the unbudgeted row of the existing table also asserts that the handle got
`assertSame(Lease.NO_BUDGET, ...)`; today that case checks only that nothing was reserved.

## Out of scope

- Waiter priority. Making freed bytes accumulate for the oldest waiter (pending heap/direct counters checked
  by `acquire` and `tryReserve`) removes starvation, but makes servers refuse connections while background
  work drains capacity, and several waiters call for a FIFO queue rather than a monitor. Revisit when a real
  workload has connections and blocking reservations competing on one budget.
- No change to `RestContext`. Its blocking twins of `reserveMemory` would be traps rather than API: a
  `RestContext` is read on the event loop of its channel and every handle starts there, so a call which
  waits would block that loop. A handle which answers later and reserves on a thread of its own already
  holds the budget it gave its server and calls `reserve` / the timed `tryReserve` on it; turning `null`
  into `503` there is one `result.error(...)`, not a method. `reserveMemory` exists for what only the
  context knows - the server's budget, `Lease.NO_BUDGET` without one, and `503` - and stays immediate.
- A REST reservation which waits without blocking: a lease delivered on `RestContext.executor()` when the
  room appears, `503` when the handle's deadline passes first. That is a list of asynchronous waiters
  served on release, not the monitor this plan adds, and it waits for a workload which needs it. It would
  keep the current split: `Lease.NO_BUDGET` at once without a budget, `503` when the budget has no room in time.