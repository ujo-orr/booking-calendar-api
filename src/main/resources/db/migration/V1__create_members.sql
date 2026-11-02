CREATE TABLE members (
    id BIGINT NOT NULL AUTO_INCREMENT,
    provider VARCHAR(20) NOT NULL,
    provider_id VARCHAR(255) NOT NULL,
    email VARCHAR(320),
    full_name VARCHAR(255),
    PRIMARY KEY (id),
    CONSTRAINT uk_member_provider_provider_id UNIQUE (provider, provider_id)
);
