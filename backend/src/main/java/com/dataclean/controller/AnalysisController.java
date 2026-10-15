package com.dataclean.controller;

import com.dataclean.dto.ChartDataVO;
import com.dataclean.dto.ChartRequestDTO;
import com.dataclean.dto.Result;
import com.dataclean.service.AnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 数据分析控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
public class AnalysisController {

    private final AnalysisService analysisService;

    /**
     * 生成图表数据
     */
    @PostMapping("/chart")
    public Result<ChartDataVO> generateChart(@RequestBody ChartRequestDTO dto) {
        return Result.success(analysisService.generateChartData(dto));
    }

    /**
     * 获取统计数据
     */
    @GetMapping("/statistics/{datasetId}")
    public Result<Map<String, Object>> getStatistics(@PathVariable Long datasetId) {
        return Result.success(analysisService.getStatistics(datasetId));
    }
}
