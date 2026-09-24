package io.github.kardane.jarvisminecraft.paper.platform;

import io.github.kardane.jarvisminecraft.common.runtime.ServerScheduler;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

public final class PaperServerScheduler implements ServerScheduler {
    private final Plugin plugin;

    public PaperServerScheduler(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public <T> CompletionStage<T> submit(Supplier<CompletionStage<T>> task) {
        CompletableFuture<T> output = new CompletableFuture<>();
        Runnable invoke = () -> {
            try {
                CompletionStage<T> stage = task.get();
                stage.whenComplete((value, failure) -> {
                    if (failure != null) {
                        output.completeExceptionally(failure);
                    } else {
                        output.complete(value);
                    }
                });
            } catch (Throwable failure) {
                output.completeExceptionally(failure);
            }
        };

        if (Bukkit.isPrimaryThread()) {
            invoke.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, invoke);
        }
        return output;
    }
}
