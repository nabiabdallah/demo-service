package com.example.demo.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.application.port.in.GreetCommand;
import com.example.demo.application.port.in.GreetUseCase;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class GreetServiceTest {

    private final GreetUseCase service = new GreetService();

    @Test
    void greets_the_given_name() { // AC1, AC11
        assertThat(service.greet(new GreetCommand("Alice")).greeting()).isEqualTo("Hello, Alice!");
    }

    @Test
    void keeps_non_ascii_and_markup_unchanged() { // AC6, AC7
        assertThat(service.greet(new GreetCommand("Zoë")).greeting()).isEqualTo("Hello, Zoë!");
        assertThat(service.greet(new GreetCommand("李雷")).greeting()).isEqualTo("Hello, 李雷!");
        assertThat(service.greet(new GreetCommand("<b>x</b>")).greeting()).isEqualTo("Hello, <b>x</b>!");
    }

    @Test
    void concurrent_greetings_each_contain_only_their_own_name() throws Exception { // AC10
        int n = 16;
        var barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String name = "caller" + i + "x";
                results.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    String last = null;
                    for (int k = 0; k < 200; k++) {
                        last = service.greet(new GreetCommand(name)).greeting();
                        if (!last.equals("Hello, " + name + "!")) {
                            return last;
                        }
                    }
                    return last;
                }));
            }
            for (int i = 0; i < n; i++) {
                assertThat(results.get(i).get(30, TimeUnit.SECONDS)).isEqualTo("Hello, caller" + i + "x!");
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
