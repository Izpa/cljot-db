CREATE TABLE file (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    original_chat_id BIGINT NOT NULL,
    original_message_id BIGINT NOT NULL,
    original_file_id TEXT,
    storage_key TEXT,
    is_circle BOOLEAN,
    name TEXT NOT NULL
);
