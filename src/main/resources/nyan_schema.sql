CREATE TABLE IF NOT EXISTS rng_seeds_raw (
    received_at INTEGER NOT NULL,
    rng_seed   INTEGER NOT NULL,

    UNIQUE(received_at, rng_seed) -- easier to merge these if we set a precedent of no duplicates ever
    CHECK(received_at > 0),
    CHECK(
        (rng_seed = -1) -- rng_state -1 will mean that we were unable to determine it (latticg failed)
        OR (rng_seed >= 0 AND rng_seed < (1 << 48)) -- allow zero just in case, 1/2^48 could happen :)
    )
);
CREATE INDEX IF NOT EXISTS rng_seeds_raw_by_received_at ON rng_seeds_raw(received_at);