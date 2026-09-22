-- Reference schema for 在线协作文档编辑系统.
-- This file is documentation, not a migration: the live schema is produced by the JPA entities through
-- `spring.jpa.hibernate.ddl-auto: update`, so it is never executed at startup. Keep it aligned with
-- backend/src/main/java/com/collabdoc/entity/*.java by hand.
--
-- generated: MySQL 8 / Hibernate 6.4 — boolean maps to BIT(1), LocalDateTime to DATETIME(6).
--
-- No FOREIGN KEY constraints: the entities declare plain columns for these references, so Hibernate's
-- generated schema has none either, and this file must match it. Referential integrity is enforced in the
-- service layer instead — AdminService.deleteUser removes the user's documents, shares and operation logs
-- in one transaction because the database will not do it.

CREATE DATABASE IF NOT EXISTS collabdoc
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE collabdoc;

-- 用户表
CREATE TABLE IF NOT EXISTS `user` (
    `id`            BIGINT AUTO_INCREMENT PRIMARY KEY,
    `user_code`     VARCHAR(8)   NOT NULL COMMENT '8 位分享码',
    `username`      VARCHAR(100) NOT NULL,
    `email`         VARCHAR(255) NOT NULL,
    `password_hash` VARCHAR(255) NOT NULL,
    `role`          VARCHAR(20)  NOT NULL COMMENT 'ADMIN | USER',
    `enabled`       BIT(1)       NOT NULL,
    `created_at`    DATETIME(6)  NOT NULL,
    UNIQUE KEY `uk_user_code` (`user_code`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_email` (`email`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 文档表：version 是协同序列号，只增不减；revision 是 JPA 乐观锁列
CREATE TABLE IF NOT EXISTS `document` (
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY,
    `title`          VARCHAR(255) NOT NULL,
    `content`        LONGTEXT     NOT NULL COMMENT '最新检查点内容',
    `content_format` VARCHAR(20)  NOT NULL COMMENT 'html | doc-json',
    `version`        INT          NOT NULL,
    `revision`       BIGINT       NOT NULL,
    `created_by`     BIGINT       NOT NULL,
    `created_at`     DATETIME(6)  NOT NULL,
    `updated_at`     DATETIME(6)  NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 操作日志：可重放的协同历史。STEPS 的 command_params 形如
-- {"baseVersion":3,"clientId":"c-7f2a","docSize":128,"steps":[{...}]}
CREATE TABLE IF NOT EXISTS `operation_log` (
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY,
    `document_id`    BIGINT      NOT NULL,
    `user_id`        BIGINT      NOT NULL,
    `command_type`   VARCHAR(50) NOT NULL COMMENT 'STEPS | SAVE | RESTORE | CHECKPOINT',
    `command_params` JSON,
    `version`        INT         NOT NULL,
    `created_at`     DATETIME(6) NOT NULL,
    UNIQUE KEY `uk_oplog_doc_version` (`document_id`, `version`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 检查点：每 CHECKPOINT_EVERY 个版本一份，每文档最多保留 50 份
CREATE TABLE IF NOT EXISTS `document_snapshot` (
    `id`             BIGINT AUTO_INCREMENT PRIMARY KEY,
    `document_id`    BIGINT      NOT NULL,
    `content`        LONGTEXT    NOT NULL,
    `content_format` VARCHAR(20) NOT NULL,
    `version`        INT         NOT NULL,
    `created_at`     DATETIME(6) NOT NULL,
    UNIQUE KEY `uk_snapshot_doc_version` (`document_id`, `version`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 分享权限
CREATE TABLE IF NOT EXISTS `document_share` (
    `id`          BIGINT AUTO_INCREMENT PRIMARY KEY,
    `document_id` BIGINT      NOT NULL,
    `user_id`     BIGINT      NOT NULL,
    `permission`  VARCHAR(255) NOT NULL COMMENT 'READ_WRITE | READ_ONLY',
    `shared_by`   BIGINT      NOT NULL,
    `created_at`  DATETIME(6) NOT NULL,
    UNIQUE KEY `uk_doc_user` (`document_id`, `user_id`),
    INDEX `idx_share_user` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 站内通知：先落库再实时推送，接收者离线也能看到
CREATE TABLE IF NOT EXISTS `notification` (
    `id`          BIGINT AUTO_INCREMENT PRIMARY KEY,
    `user_id`     BIGINT       NOT NULL,
    `document_id` BIGINT,
    `type`        VARCHAR(30)  NOT NULL,
    `message`     VARCHAR(255) NOT NULL,
    `read_flag`   BIT(1)       NOT NULL,
    `created_at`  DATETIME(6)  NOT NULL,
    INDEX `idx_notif_user_read` (`user_id`, `read_flag`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ⚠️ 若把某个 profile 指向由旧版代码写过的库：两张子表的唯一约束可能因存量重复序列号而建立失败，
--    先执行 `select document_id, version, count(*) c from operation_log group by document_id, version
--    having c > 1`（document_snapshot 同）查清并去重。
