package org.bb.bb_economy.database;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.bb.bb_economy.Config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SalaryScheduler {

    private final ExecutorService salaryExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "bb-salary-processor");
        thread.setDaemon(true);
        return thread;
    });

    private long lastProcessedGameDay = -1;

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!DatabaseManager.isConnected()) return;

        long dayTime = event.getServer().overworld().getDayTime();
        long gameDay = dayTime / 24000L;
        long tick = dayTime % 24000L;

        if (tick != Config.SALARY_RECURRENCE_TICK) return;
        if (gameDay % Config.SALARY_RECURRENCE_DAYS != 0) return;
        if (gameDay == lastProcessedGameDay) return;
        lastProcessedGameDay = gameDay;

        salaryExecutor.submit(() -> BankManager.processSalaries(dayTime));
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        salaryExecutor.shutdownNow();
    }
}
