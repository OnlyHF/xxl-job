package com.xxl.job.jdk;

import org.junit.jupiter.api.Test;

public class StackWalkerTest {

    @Test
    public void test() {
        StackWalker walker = StackWalker.getInstance();
        walker.forEach(frame -> {
            System.out.println("类名: " + frame.getClassName());
            System.out.println("方法名: " + frame.getMethodName());
            System.out.println("行号: " + frame.getLineNumber());
            System.out.println("-----------------");
        });

        // 配置保留类引用
        walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

        // 查找第一个非当前类的调用者
        Class<?> caller = walker.getCallerClass();
        System.out.println("调用者类: " + caller.getName());


        walker = StackWalker.getInstance();
        boolean isCalledByService = walker.walk(stream ->
                stream.anyMatch(frame -> frame.getClassName().equals("com.xxl.job.jdk.StackWalkerTest"))
        );
        System.out.println("是否被MyService调用: " + isCalledByService);

    }

}
