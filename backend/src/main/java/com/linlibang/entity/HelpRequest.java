package com.linlibang.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 求助实体
 */
@Data
public class HelpRequest {

    /** 主键ID */
    private Long id;

    /** 发布者用户ID */
    private Long userId;

    /** 求助分类ID */
    private Long categoryId;

    /** 标题 */
    private String title;

    /** 详细描述 */
    private String description;

    /** 图片（多张用逗号分隔） */
    private String images;

    /** 悬赏金额（每人单价，支付总额=单价×需要人数） */
    private BigDecimal reward;

    /** 支付总额（单价×需要人数，发布时计算） */
    private BigDecimal totalReward;

    /** 地址 */
    private String address;

    /** 经度 */
    private Double lng;

    /** 纬度 */
    private Double lat;

    /** 状态：1招募中 2已满员(进行中) 3已完成 4已取消 */
    private Integer status;

    /** 需要人数（多人求助，默认1） */
    private Integer helperNum;

    /** 已接人数（达到 helperNum 后状态自动转2） */
    private Integer acceptedNum;

    /** 是否紧急：0不紧急 1紧急 */
    private Integer urgent;

    /** 浏览次数 */
    private Integer viewCount;

    /** 乐观锁版本号（每次修改 +1） */
    private Integer version;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /** 逻辑删除：0未删除 1已删除 */
    private Integer isDeleted;
}
