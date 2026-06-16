package com.auteur.agent;

/**
 * 默认 risk = ACTION。
 *
 * 实现这个接口即默认 ACTION,避免漏写 @Override risk() 静默下沉到 READ 绕过 HITL 审批。
 */
public interface ActionToolHandler extends ToolHandler {
    @Override
    default Risk risk() { return Risk.ACTION; }
}
