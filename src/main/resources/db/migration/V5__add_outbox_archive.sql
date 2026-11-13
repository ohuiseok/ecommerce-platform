-- 원본 이벤트의 모든 필드를 보존하되 아카이브에는 ID 자동 생성을 두지 않는다.
CREATE TABLE outbox_event_archive (LIKE outbox_events INCLUDING CONSTRAINTS);
ALTER TABLE outbox_event_archive ADD PRIMARY KEY (outbox_event_id);
ALTER TABLE outbox_event_archive ADD CONSTRAINT uk_outbox_archive_event_id UNIQUE (event_id);
CREATE INDEX idx_outbox_events_published_cleanup
    ON outbox_events (published_at, outbox_event_id) WHERE status = 'PUBLISHED';
