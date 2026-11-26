package com.dataclean.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class DashboardVO {
    private Long id;
    private String title;
    private String description;
    private String ownerType;
    private Long itemCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<DashboardItemVO> items;
}
