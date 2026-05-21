CREATE DATABASE IF NOT EXISTS collabdoc
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE collabdoc;

-- 用户表
CREATE TABLE IF NOT EXISTS `user` (
    `id`            BIGINT AUTO_INCREMENT PRIMARY KEY,
    `username`      VARCHAR(100) NOT NULL,
    `email`         VARCHAR(255) NOT NULL,
    `password_hash` VARCHAR(255) NOT NULL,
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 文档表
CREATE TABLE IF NOT EXISTS `document` (
    `id`         BIGINT AUTO_INCREMENT PRIMARY KEY,
    `title`      VARCHAR(255) NOT NULL,
    `content`    LONGTEXT     NOT NULL,
    `version`    INT          NOT NULL DEFAULT 0,
    `created_by` BIGINT       NOT NULL,
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT `fk_doc_creator` FOREIGN KEY (`created_by`) REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 操作日志表
CREATE TABLE IF NOT EXISTS `operation_log` (
    `id`            BIGINT AUTO_INCREMENT PRIMARY KEY,
    `document_id`   BIGINT       NOT NULL,
    `user_id`       BIGINT       NOT NULL,
    `command_type`  VARCHAR(50)  NOT NULL,
    `command_params` JSON,
    `version`       INT          NOT NULL,
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT `fk_oplog_doc`  FOREIGN KEY (`document_id`) REFERENCES `document` (`id`),
    CONSTRAINT `fk_oplog_user` FOREIGN KEY (`user_id`)     REFERENCES `user` (`id`),
    INDEX `idx_oplog_doc_version` (`document_id`, `version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 文档快照表
CREATE TABLE IF NOT EXISTS `document_snapshot` (
    `id`          BIGINT AUTO_INCREMENT PRIMARY KEY,
    `document_id` BIGINT   NOT NULL,
    `content`     LONGTEXT NOT NULL,
    `version`     INT      NOT NULL,
    `created_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT `fk_snap_doc` FOREIGN KEY (`document_id`) REFERENCES `document` (`id`),
    INDEX `idx_snap_doc_version` (`document_id`, `version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 文档分享表
CREATE TABLE IF NOT EXISTS `document_share` (
    `id`          BIGINT AUTO_INCREMENT PRIMARY KEY,
    `document_id` BIGINT       NOT NULL,
    `user_id`     BIGINT       NOT NULL,
    `permission`  VARCHAR(20)  NOT NULL DEFAULT 'READ_WRITE',
    `shared_by`   BIGINT       NOT NULL,
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT `fk_share_doc`  FOREIGN KEY (`document_id`) REFERENCES `document` (`id`),
    CONSTRAINT `fk_share_user` FOREIGN KEY (`user_id`)     REFERENCES `user` (`id`),
    CONSTRAINT `fk_share_by`   FOREIGN KEY (`shared_by`)   REFERENCES `user` (`id`),
    UNIQUE KEY `uk_doc_user` (`document_id`, `user_id`),
    INDEX `idx_share_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
