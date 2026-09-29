-- 수집기 전용 읽기 계정 (로컬 테스트용). SHOW GLOBAL STATUS/VARIABLES와 information_schema 조회만 필요하다.
CREATE USER IF NOT EXISTS 'monitor'@'%' IDENTIFIED BY 'monitor';
GRANT PROCESS ON *.* TO 'monitor'@'%';
GRANT SELECT ON sample_app.* TO 'monitor'@'%';

-- storageBytes 확인용 샘플 테이블
CREATE TABLE IF NOT EXISTS sample_app.orders (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    item       VARCHAR(100) NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO sample_app.orders (item) VALUES ('keyboard'), ('mouse'), ('monitor');
