package com.veerayaaa.youtubetomp3bot.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.DefaultAbsSender;
import org.telegram.telegrambots.bots.DefaultBotOptions;

@Service
public class TelegramReplyClient extends DefaultAbsSender {
    @Value("${telegram.botToken}")
    private String botToken;

    protected TelegramReplyClient() {
        super(new DefaultBotOptions());
    }

    @Override
    public String getBotToken() {
        return this.botToken;
    }
}
