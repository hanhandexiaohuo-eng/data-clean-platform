package com.dataclean.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataclean.dto.DatasetPreviewVO;
import com.dataclean.dto.DatasetUploadDTO;
import com.dataclean.dto.Result;
import com.dataclean.entity.Dataset;
import com.dataclean.entity.DatasetColumn;
import com.dataclean.service.DatasetService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 数据集管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/dataset")
@RequiredArgsConstructor
public class DatasetController {

    private final DatasetService datasetService;

    /**
     * 分页查询数据集列表
     */
    @GetMapping
    public Result<Page<Dataset>> list(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String keyword) {
        return Result.success(datasetService.pageList(page, size, keyword));
    }

    /**
     * 根据ID查询数据集
     */
    @GetMapping("/{id}")
    public Result<Dataset> getById(@PathVariable Long id) {
        return Result.success(datasetService.getById(id));
    }

    /**
     * 上传文件创建数据集
     */
    @PostMapping("/upload")
    public Result<Dataset> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam String name,
            @RequestParam(required = false) String description,
            @RequestParam(required = false, defaultValue = "true") Boolean hasHeader,
            @RequestParam(required = false, defaultValue = "UTF-8") String encoding) {

        DatasetUploadDTO dto = new DatasetUploadDTO();
        dto.setName(name);
        dto.setDescription(description);
        dto.setHasHeader(hasHeader);
        dto.setEncoding(encoding);

        Dataset dataset = datasetService.uploadFile(file, dto);
        return Result.success("上传成功", dataset);
    }

    /**
     * 预览数据
     */
    @GetMapping("/{id}/preview")
    public Result<DatasetPreviewVO> preview(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size) {
        return Result.success(datasetService.previewData(id, page, size));
    }

    /**
     * 获取列信息
     */
    @GetMapping("/{id}/columns")
    public Result<List<DatasetColumn>> getColumns(@PathVariable Long id) {
        return Result.success(datasetService.getColumns(id));
    }

    /**
     * 获取数据集统计信息(用于AI分析)
     */
    @GetMapping("/{id}/statistics")
    public Result<Map<String, Object>> getStatistics(@PathVariable Long id) {
        return Result.success(datasetService.getDatasetStatistics(id));
    }

    /**
     * 删除数据集
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        datasetService.deleteDataset(id);
        return Result.success();
    }
}
