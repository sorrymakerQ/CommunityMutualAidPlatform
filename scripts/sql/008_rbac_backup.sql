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
-- Table structure for table `tb_role`
--

DROP TABLE IF EXISTS `tb_role`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tb_role` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '角色ID',
  `code` varchar(32) NOT NULL COMMENT '角色编码（Sa-Token 校验用，小写）：admin/user/reviewer',
  `name` varchar(32) NOT NULL COMMENT '角色名称：管理员/普通用户/审核员',
  `description` varchar(128) DEFAULT NULL COMMENT '描述',
  `status` tinyint DEFAULT '1' COMMENT '状态 1启用 0禁用',
  `is_builtin` tinyint DEFAULT '0' COMMENT '系统内置角色(1=内置不可删除)',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色表（身份）';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `tb_role`
--

LOCK TABLES `tb_role` WRITE;
/*!40000 ALTER TABLE `tb_role` DISABLE KEYS */;
INSERT INTO `tb_role` VALUES (1,'admin','管理员','平台管理员，拥有全部权限',1,1,'2026-09-01 18:02:50'),(2,'user','普通用户','默认角色：可发布求助、接单、私信',1,0,'2026-09-01 18:02:50'),(3,'reviewer','审核员','审核求助内容',1,0,'2026-09-01 18:02:50'),(4,'super_admin','超级管理员','系统最高权限：可管理管理员，拥有全部权限',1,1,'2026-09-01 18:44:56');
/*!40000 ALTER TABLE `tb_role` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `tb_permission`
--

DROP TABLE IF EXISTS `tb_permission`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tb_permission` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '权限ID',
  `code` varchar(64) NOT NULL COMMENT '权限码（对应 @SaCheckPermission 的值）：help:publish 等',
  `name` varchar(32) NOT NULL COMMENT '功能名：发布求助/接单/审核求助',
  `type` tinyint DEFAULT '1' COMMENT '类型 1接口 2按钮 3菜单',
  `sort` int DEFAULT '0' COMMENT '排序',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`)
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='权限表（功能点）';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `tb_permission`
--

LOCK TABLES `tb_permission` WRITE;
/*!40000 ALTER TABLE `tb_permission` DISABLE KEYS */;
INSERT INTO `tb_permission` VALUES (1,'help:publish','发布求助',1,0,'2026-09-01 18:02:50'),(2,'order:accept','接单',1,0,'2026-09-01 18:02:50'),(3,'message:send','发送私信',1,0,'2026-09-01 18:02:50'),(4,'help:audit','审核求助',1,0,'2026-09-01 18:02:50'),(5,'user:manage','用户管理',1,0,'2026-09-01 18:02:50');
/*!40000 ALTER TABLE `tb_permission` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `tb_role_permission`
--

DROP TABLE IF EXISTS `tb_role_permission`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tb_role_permission` (
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `permission_id` bigint NOT NULL COMMENT '权限ID',
  PRIMARY KEY (`role_id`,`permission_id`),
  KEY `idx_permission_id` (`permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色-权限关联表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `tb_role_permission`
--

LOCK TABLES `tb_role_permission` WRITE;
/*!40000 ALTER TABLE `tb_role_permission` DISABLE KEYS */;
INSERT INTO `tb_role_permission` VALUES (1,1),(2,1),(3,1),(4,1),(1,2),(2,2),(3,2),(4,2),(1,3),(2,3),(3,3),(4,3),(1,4),(3,4),(4,4),(1,5),(4,5);
/*!40000 ALTER TABLE `tb_role_permission` ENABLE KEYS */;
UNLOCK TABLES;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-09-30 20:58:42
