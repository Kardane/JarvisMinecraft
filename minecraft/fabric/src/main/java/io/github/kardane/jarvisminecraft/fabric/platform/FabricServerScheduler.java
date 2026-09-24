package io.github.kardane.jarvisminecraft.fabric.platform;

import io.github.kardane.jarvisminecraft.common.runtime.ServerScheduler;
import net.minecraft.server.MinecraftServer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

public final class FabricServerScheduler implements ServerScheduler {
    private final MinecraftServer server;

    public FabricServerScheduler(MinecraftServer server) {
        this.server = server;
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

        if (server.isOnThread()) {
            invoke.run();
        } else {
            server.execute(invoke);
        }
        return output;
    }
}
