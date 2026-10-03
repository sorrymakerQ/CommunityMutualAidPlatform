-- MySQL dump 10.13  Distrib 8.0.31, for Win64 (x86_64)
--
-- Host: localhost    Database: linlibang
-- ------------------------------------------------------
-- Server version	8.0.31

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Table structure for table `tb_order`
--

DROP TABLE IF EXISTS `tb_order`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tb_order` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '订单ID',
  `help_id` bigint NOT NULL COMMENT '求助ID',
  `publisher_id` bigint NOT NULL COMMENT '发布者ID',
  `helper_id` bigint NOT NULL COMMENT '接单者ID',
  `status` tinyint DEFAULT '1' COMMENT '状态 1已接单 2进行中 3已完成 4已取消 5已评价',
  `cancel_reason` varchar(256) DEFAULT NULL COMMENT '取消原因',
  `accept_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '接单时间',
  `finish_time` datetime DEFAULT NULL COMMENT '完成时间',
  `publisher_score` tinyint DEFAULT NULL COMMENT '发布者评分 1-5',
  `helper_score` tinyint DEFAULT NULL COMMENT '接单者评分 1-5',
  `publisher_comment` varchar(512) DEFAULT NULL COMMENT '发布者评价',
  `helper_comment` varchar(512) DEFAULT NULL COMMENT '接单者评价',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号（每次修改+1，评价/取消/完成时用于并发更新校验）',
  `is_deleted` tinyint DEFAULT '0' COMMENT '逻辑删除（业务禁止物理删除订单）',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `seq` tinyint NOT NULL DEFAULT '0' COMMENT '接单序号:活跃=1,已取消=0(释放唯一键); (help_id,helper_id,seq) 唯一,取消后可重新接单',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_help_helper_seq` (`help_id`,`helper_id`,`seq`),
  KEY `idx_help_id` (`help_id`),
  KEY `idx_publisher_id` (`publisher_id`),
  KEY `idx_helper_id` (`helper_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=4000001 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-10-01  1:46:39
