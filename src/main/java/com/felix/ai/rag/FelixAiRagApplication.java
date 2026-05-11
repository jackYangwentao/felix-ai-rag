package com.felix.ai.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Felix AI RAG Application Entry Point
 * 基于 LangChain4J 的 RAG (Retrieval-Augmented Generation) 应用
 * 支持本地模型通过 Ollama 接入
 */
@SpringBootApplication
public class FelixAiRagApplication {

    public static void main(String[] args) {
        SpringApplication.run(FelixAiRagApplication.class, args);
    }
}
