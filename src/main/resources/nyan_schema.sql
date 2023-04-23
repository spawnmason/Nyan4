CREATE TABLE IF NOT EXISTS rng_seeds_raw (
    received_at INTEGER NOT NULL,
    rng_seed   INTEGER NOT NULL,
    processed BOOLEAN NOT NULL DEFAULT FALSE,

    UNIQUE(rng_seed, received_at) -- easier to merge these if we set a precedent of no duplicates ever
    CHECK(received_at > 0),
    CHECK(
        (rng_seed = -1) -- rng_state -1 will mean that we were unable to determine it (latticg failed)
        OR (rng_seed >= 0 AND rng_seed < (1 << 48)) -- allow zero just in case, 1/2^48 could happen :)
    )
);
CREATE INDEX IF NOT EXISTS rng_seeds_raw_by_received_at ON rng_seeds_raw(received_at);
CREATE INDEX IF NOT EXISTS rng_seeds_raw_not_yet_processed ON rng_seeds_raw(rng_seed) WHERE NOT processed;

CREATE TABLE IF NOT EXISTS rng_seeds_processed (
    rng_seed INTEGER NOT NULL PRIMARY KEY,
    steps_back INTEGER NOT NULL,
    woodland_x INTEGER NOT NULL,
    woodland_z INTEGER NOT NULL

    CHECK(rng_seed >= 0 AND rng_seed < (1 << 48)),
    CHECK(steps_back >= 0),
    CHECK(woodland_x >= -23440 AND woodland_x <= 23440),
    CHECK(woodland_z >= -23440 AND woodland_z <= 23440)
);