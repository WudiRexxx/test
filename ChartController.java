package com.fitech.fitechstudybackend.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fitech.fitechstudybackend.common.BizException;
import com.fitech.fitechstudybackend.config.ReportFileProperties;
import com.fitech.fitechstudybackend.common.Result;
import com.fitech.fitechstudybackend.entity.Project;
import com.fitech.fitechstudybackend.entity.TemplateField;
import com.fitech.fitechstudybackend.enums.FieldType;
import com.fitech.fitechstudybackend.mapper.ProjectMapper;
import com.fitech.fitechstudybackend.mapper.TemplateFieldMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

@RestController
@RequestMapping("/charts")
@RequiredArgsConstructor
public class ChartController {
    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };
    private static final long MAX_CHART_SIZE = 2 * 1024 * 1024;
    private static final int MAX_WIDTH = 4096;
    private static final int MAX_HEIGHT = 4096;

    private final ReportFileProperties reportFileProperties;
    private final ProjectMapper projectMapper;
    private final TemplateFieldMapper templateFieldMapper;
    @PostMapping("/upload")
    public Result upload( @RequestParam Long projectId,
                          @RequestParam String fieldKey, MultipartFile file,
                          @RequestParam Long templateId) throws IOException {
        if (projectId == null || projectId <= 0) {
            throw new BizException("项目ID不合法");
        }
        if (fieldKey == null || !fieldKey.matches("[A-Za-z0-9_]{1,64}")) {
            throw new BizException("字段名不合法");
        }
        if (templateId == null || templateId <= 0) {
            throw new BizException("模板ID不合法");
        }
        if (file == null || file.isEmpty()) {
            throw new BizException("图表文件为空");
        }
        if (file.getSize() > MAX_CHART_SIZE) {
            throw new BizException("图表不能超过2MB");
        }
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BizException("项目不存在");
        }
        TemplateField templateField = templateFieldMapper.selectOne(
                new LambdaQueryWrapper<TemplateField>().eq(TemplateField::getTemplateId, templateId)
                        .eq(TemplateField::getFieldKey, fieldKey));
        if (templateField == null) {
            throw new BizException("模板字段不存在");
        }
        if (templateField.getFieldType() != FieldType.IMAGE) {
            throw new BizException("字段[" + fieldKey + "]不是图片字段");
        }
        Path dir = Paths.get(reportFileProperties.getStorageDir(), "charts", String.valueOf(projectId));
        Files.createDirectories(dir);

        Path target = dir.resolve(fieldKey+".png");
        Path temp = null;
        try {
            temp = Files.createTempFile(dir, "chart-", ".tmp");
            file.transferTo(temp);
            //将上传的 PNG 图片文件解码成 Java 内存中的图像对象（BufferedImage），用于后续的校验和操作。
            BufferedImage image = decodePng(temp);
            if (image.getWidth() > MAX_WIDTH || image.getHeight() > MAX_HEIGHT) {
                throw new BizException("图表尺寸不能超过4096x4096");
            }
            Files.move(temp, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            temp = null;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("图表上传失败");
        } finally {
            deleteQuietly(temp);
        }
        return Result.ok(null);
    }

    private BufferedImage decodePng(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            //PNG 文件头校验
            byte[] signature = in.readNBytes(PNG_SIGNATURE.length);
            if (!Arrays.equals(signature, PNG_SIGNATURE)) {
                throw new BizException("图表只能是PNG图片");
            }
        }
        BufferedImage image;
        try (InputStream in = Files.newInputStream(path)) {
            image = ImageIO.read(in);//尝试解码图片
        }
        if (image == null) {
            throw new BizException("图表图片内容不合法");
        }
        return image;
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}
