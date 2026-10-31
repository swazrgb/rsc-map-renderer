/** The rejection of a call that a newer call replaced before it started. */
export class SupersededError extends Error {
    constructor() {
        super("superseded by a newer plan");
    }
}

/**
 * Wraps `run` so it runs one call at a time, latest wins: while one call runs only the newest
 * further call waits, and each waiting call it replaces rejects with {@link SupersededError}.
 */
export function latestSlot<A, R>(run: (arg: A) => Promise<R>): (arg: A) => Promise<R> {
    let running = false;
    let pending: {arg: A; resolve: (r: R) => void; reject: (e: Error) => void} | null = null;
    const pump = () => {
        if (running || !pending) return;
        const job = pending;
        pending = null;
        running = true;
        run(job.arg)
            .then(job.resolve, job.reject)
            .finally(() => {
                running = false;
                pump();
            });
    };
    return (arg: A) =>
        new Promise<R>((resolve, reject) => {
            pending?.reject(new SupersededError());
            pending = {arg, resolve, reject};
            pump();
        });
}
