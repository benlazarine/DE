--
-- The table for storing permission levels that can be applied to resources.
--
CREATE TABLE permission_levels (
    id uuid NOT NULL DEFAULT uuid_generate_v1(),
    name varchar(64) NOT NULL,
    description text NOT NULL,
    precedence integer NOT NULL,
    PRIMARY KEY (id)
);