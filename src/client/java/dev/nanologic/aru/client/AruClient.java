package dev.nanologic.aru.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.text.Text;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class AruClient implements ClientModInitializer {
    private TitleScreen _titleScreen;
    private static final String ETAG_FILENAME = "Aeternum.zip.etag";

    @Override
    public void onInitializeClient() {
        ScreenEvents.AFTER_INIT.register(((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof TitleScreen titleScreen) {
                this._titleScreen = titleScreen;

                ButtonWidget resourcePackButton = ButtonWidget.builder(
                        Text.literal("AE"),
                        button -> {

                            try {
                                downloadAndApplyResourcepack();
                            } catch (IOException | URISyntaxException | InterruptedException e) {
                                ToastManager toastManager = client.getToastManager();
                                showErrorToast(toastManager);
                            }
                        }
                )
                .dimensions(
                    titleScreen.width / 2 - 124,
                    titleScreen.height / 4 + 72,
                        20,
                        20
                )
                .build();

                Screens.getButtons(titleScreen).add(resourcePackButton);
            }
        }));
    }

    private void showErrorToast(ToastManager toastManager) {
        SystemToast.show(toastManager, SystemToast.Type.PERIODIC_NOTIFICATION, Text.literal("Aeternum"), Text.literal("Ein unerwarteter Fehelr ist aufgetreten!"));
    }

    private void downloadAndApplyResourcepack() throws IOException, URISyntaxException, InterruptedException {
        // Get client and resourcepackManager
        MinecraftClient client = MinecraftClient.getInstance();
        ResourcePackManager resourcePackManager = client.getResourcePackManager();
        ToastManager toastManager = client.getToastManager();

        // Get resourcepacks directory and the target path for the new Aeternum resourcepack
        Path resourcePacksDir = client.getResourcePackDir();
        Path target = resourcePacksDir.resolve("Aeternum.zip");

        if (isUpToDate(resourcePacksDir, "https://resourcepack.aeternum-roleplay.de/Aeternum.zip")) {
            SystemToast.show(toastManager, SystemToast.Type.PERIODIC_NOTIFICATION, Text.literal("Aeternum"), Text.literal("Du hast bereits die neuste Version des Resourcepacks!"));
            return;
            //downloadFile("https://resourcepack.aeternum-roleplay.de/Aeternum.zip", target);
        }

        // Disable buttons to start the process
        toggleButtons(false);

        // Scan for resourcepacks and then disable Aeternum resourcepack if it exists.
        resourcePackManager.scanPacks();
        resourcePackManager.getProfiles().forEach(profile -> {
            if (profile.getId().equals("file/Aeternum.zip")) {
                resourcePackManager.disable(profile.getId());
            }
        });

        // Reload the resourcepacks
        client.reloadResources().whenCompleteAsync((client1, throwable) -> {
            if (throwable != null) {
                // If we get an error enable the buttons again
                toggleButtons(true);
            } else {
                // After resourcepacks have been reloaded we can continue
                CompletableFuture.runAsync(() -> {
                    try {
                        // Download the Aeternum resourcepack
                        downloadFile("https://resourcepack.aeternum-roleplay.de/Aeternum.zip", target);

                        // Once the Aeternum resourcepack has been downloaded continue
                        client.execute(() -> {
                            // Scan for available packs once more and enable the Aeternum resourcepack
                            resourcePackManager.scanPacks();
                            resourcePackManager.getProfiles().forEach(profile -> {
                                if (profile.getId().equals("file/Aeternum.zip")) {
                                    resourcePackManager.enable(profile.getId());
                                }
                            });
                            // Finally reload the resourcepacks once more
                            client.reloadResources();

                            // And then re-enable the menu buttons
                            toggleButtons(true);

                            SystemToast.show(toastManager, SystemToast.Type.PERIODIC_NOTIFICATION, Text.literal("Aeternum"), Text.literal("Das Resourcepack wurde erfolgreich geupdated!"));

                        });
                    } catch (Exception e) {
                        //throw new RuntimeException(e);
                        toggleButtons(true);

                    }
                });
            }
        });
    }

    private void downloadFile(String url, Path target) throws IOException, InterruptedException, URISyntaxException {
        HttpClient httpClient = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URL(url).toURI())
                .GET()
                .build();

        HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(target));

        // Store the ETag for next time if the server sent one
        response.headers().firstValue("ETag").ifPresent(etag -> {
            try {
                Files.writeString(target.resolveSibling(ETAG_FILENAME), etag);
            } catch (IOException e) {
                // Non-fatal, we'll just re-download next time
            }
        });
    }

    private void toggleButtons(boolean state) {
        Screens.getButtons(_titleScreen).forEach(resourcePackButton -> {
            resourcePackButton.active = state;
        });
    }

    private boolean isUpToDate(Path resourcePacksDir, String url) throws IOException, InterruptedException, URISyntaxException {
        Path etagFile = resourcePacksDir.resolve(ETAG_FILENAME);
        if (!Files.exists(resourcePacksDir.resolve("Aeternum.zip")) || !Files.exists(etagFile)) return false;

        String storedEtag = Files.readString(etagFile).trim();

        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URL(url).toURI())
                .header("If-None-Match", storedEtag)
                .GET()
                .build();

        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        return response.statusCode() == 304;
    }
}
