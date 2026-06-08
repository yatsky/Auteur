package com.auteur.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Pipeline 异步执行器:ImageGen / ImageAudit 这种长任务走这里。
 *
 * 池大小依据:
 *  - 上游图像 API RPM=20,单 worker ~17 RPM;2 个 worker 并行就会撞上限
 *  - core=2 max=4 同时跑 4 个长任务,再多反而排队
 *  - 队列 100:相当于排队上限,超过直接拒
 */
@Slf4j
@Configuration
@EnableAsync
public class PipelineAsyncConfig {

    @Bean(name = "pipelineExecutor")
    public Executor pipelineExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(4);
        exec.setQueueCapacity(100);
        exec.setThreadNamePrefix("pipeline-");
        exec.setKeepAliveSeconds(60);
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(30);
        exec.initialize();
        log.info("[PipelineAsyncConfig] executor ready: core=2 max=4 queue=100");
        return exec;
    }

    /**
     * 镜头级并发子任务池。
     * gpt-image-2 服务端基本串行,超过 2-3 个并发就开始排队超时。
     */
    @Bean(name = "imageWorkExecutor")
    public Executor imageWorkExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(2);
        exec.setQueueCapacity(500);
        exec.setThreadNamePrefix("img-work-");
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(60);
        exec.initialize();
        log.info("[PipelineAsyncConfig] imageWorkExecutor ready: core=2 max=2 queue=500");
        return exec;
    }
}
