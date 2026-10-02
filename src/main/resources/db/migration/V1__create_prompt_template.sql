CREATE TABLE IF NOT EXISTS prompt_template (
    template_name VARCHAR(100) NOT NULL,
    version INTEGER NOT NULL,
    content TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    created_by VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (template_name, version)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_prompt_template_active
    ON prompt_template (template_name)
    WHERE active = TRUE;