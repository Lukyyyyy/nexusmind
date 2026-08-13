package com.luky.nexusmind.service;

import com.luky.nexusmind.exception.CustomException;
import com.luky.nexusmind.model.GraphPromptTemplate;
import com.luky.nexusmind.model.User;
import com.luky.nexusmind.repository.GraphPromptTemplateRepository;
import com.luky.nexusmind.repository.UserRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class GraphPromptTemplateService implements ApplicationRunner {
    private final GraphPromptTemplateRepository repository;
    private final UserRepository userRepository;

    public GraphPromptTemplateService(GraphPromptTemplateRepository repository, UserRepository userRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (repository.count() > 0) return;
        saveSeed("通用技术文档", "TECHNICAL", "适合产品、架构、技术方案和一般技术资料", true,
                "优先抽取组件、依赖、输入输出、适用场景、约束、指标及技术之间的明确关系。过滤目录、作者、引用和普通文档元数据。");
        saveSeed("学术论文", "ACADEMIC", "适合论文、研究报告和实验分析", false,
                "优先抽取研究方法、模型结构、数据集、实验任务、评价指标、基线对比、定量结果、创新点和局限。禁止从参考文献列表抽取作者、论文和引用关系。");
        saveSeed("业务制度", "POLICY", "适合制度、流程、规范和操作手册", false,
                "优先抽取角色职责、适用范围、前置条件、办理流程、审批关系、规则约束、例外条件、时限和风险。忽略落款、修订记录及普通文档元数据。");
    }

    @Transactional(readOnly = true)
    public List<TemplateResponse> list(String username) {
        boolean admin = requireUser(username).getRole() == User.Role.ADMIN;
        List<GraphPromptTemplate> values = admin
                ? repository.findAllByOrderByDefaultTemplateDescNameAsc()
                : repository.findByEnabledTrueOrderByDefaultTemplateDescNameAsc();
        return values.stream().map(value -> response(value, admin)).toList();
    }

    @Transactional
    public TemplateResponse save(String username, Long id, TemplateRequest request) {
        requireAdmin(username);
        validate(request);
        GraphPromptTemplate value = id == null ? new GraphPromptTemplate() : repository.findById(id)
                .orElseThrow(() -> new CustomException("提示词模板不存在", HttpStatus.NOT_FOUND));
        value.setName(request.name().trim());
        value.setDocumentType(request.documentType().trim().toUpperCase());
        value.setDescription(trim(request.description()));
        value.setInstructions(request.instructions().trim());
        value.setEnabled(request.enabled() == null || request.enabled());
        value.setDefaultTemplate(Boolean.TRUE.equals(request.defaultTemplate()));
        GraphPromptTemplate saved = repository.save(value);
        if (saved.isDefaultTemplate()) clearOtherDefaults(saved.getId());
        return response(saved, true);
    }

    @Transactional
    public void delete(String username, Long id) {
        requireAdmin(username);
        GraphPromptTemplate value = repository.findById(id)
                .orElseThrow(() -> new CustomException("提示词模板不存在", HttpStatus.NOT_FOUND));
        if (value.isDefaultTemplate()) throw new CustomException("默认模板不能删除", HttpStatus.CONFLICT);
        repository.delete(value);
    }

    @Transactional(readOnly = true)
    public ResolvedTemplate resolve(Long id) {
        GraphPromptTemplate value = id == null ? null : repository.findById(id).filter(GraphPromptTemplate::isEnabled).orElse(null);
        if (value == null) value = repository.findFirstByDefaultTemplateTrueAndEnabledTrue().orElse(null);
        return value == null ? new ResolvedTemplate(null, "内置通用模板", "")
                : new ResolvedTemplate(value.getId(), value.getName(), value.getInstructions());
    }

    private void saveSeed(String name, String type, String description, boolean defaultTemplate, String instructions) {
        GraphPromptTemplate value = new GraphPromptTemplate();
        value.setName(name); value.setDocumentType(type); value.setDescription(description);
        value.setDefaultTemplate(defaultTemplate); value.setEnabled(true); value.setInstructions(instructions);
        repository.save(value);
    }

    private void clearOtherDefaults(Long selectedId) {
        repository.findAll().forEach(value -> {
            if (!value.getId().equals(selectedId) && value.isDefaultTemplate()) {
                value.setDefaultTemplate(false);
                repository.save(value);
            }
        });
    }

    private void validate(TemplateRequest request) {
        if (request == null || blank(request.name()) || blank(request.documentType()) || blank(request.instructions()))
            throw new CustomException("模板名称、文档类型和抽取要求不能为空", HttpStatus.BAD_REQUEST);
        if (request.instructions().length() > 8000)
            throw new CustomException("抽取要求不能超过 8000 字", HttpStatus.BAD_REQUEST);
        if (Boolean.TRUE.equals(request.defaultTemplate()) && Boolean.FALSE.equals(request.enabled()))
            throw new CustomException("默认模板必须启用", HttpStatus.BAD_REQUEST);
    }

    private User requireUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("用户不存在", HttpStatus.NOT_FOUND));
    }
    private void requireAdmin(String username) {
        if (requireUser(username).getRole() != User.Role.ADMIN)
            throw new CustomException("只有管理员可以维护提示词模板", HttpStatus.FORBIDDEN);
    }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private TemplateResponse response(GraphPromptTemplate value, boolean editable) {
        return new TemplateResponse(value.getId(), value.getName(), value.getDocumentType(), value.getDescription(),
                value.getInstructions(), value.isEnabled(), value.isDefaultTemplate(), editable);
    }

    public record TemplateRequest(String name, String documentType, String description, String instructions,
                                  Boolean enabled, Boolean defaultTemplate) {}
    public record TemplateResponse(Long id, String name, String documentType, String description,
                                   String instructions, boolean enabled, boolean defaultTemplate, boolean editable) {}
    public record ResolvedTemplate(Long id, String name, String instructions) {}
}
