package com.dataclean.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

@Data
@TableName("anon_usage_daily")
public class AnonUsageDaily {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String scopeType;
    private String scopeKey;
    private String metric;
    private LocalDate usageDate;
    private Long counter;
}
