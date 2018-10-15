/*
 * The MIT License
 *
 * Copyright 2018 Intuit Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.intuit.karate.web;

import com.intuit.karate.Http;
import com.intuit.karate.JsonUtils;
import com.intuit.karate.ScriptValue;
import com.intuit.karate.netty.WebSocketClient;
import com.intuit.karate.netty.WebSocketListener;
import com.intuit.karate.shell.CommandThread;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author pthomas3
 */
public abstract class DevToolsDriver implements Driver, WebSocketListener {

    protected static final Logger logger = LoggerFactory.getLogger(DevToolsDriver.class);

    protected final CommandThread command;
    protected final Http http;
    protected final WebSocketClient client;

    private final WaitState waitState;

    protected final String pageId;

    private Integer _windowId;

    protected final boolean headless;
    private final long timeOut;

    protected boolean open = true;
    protected String currentUrl;

    private int nextId = 1;

    public int getNextId() {
        return nextId++;
    }

    protected DevToolsDriver(CommandThread command, Http http, String webSocketUrl, boolean headless, long timeOut) {
        this.command = command;
        this.http = http;
        this.headless = headless;
        this.timeOut = timeOut;
        this.waitState = new WaitState(timeOut);
        int pos = webSocketUrl.lastIndexOf('/');
        pageId = webSocketUrl.substring(pos + 1);
        logger.debug("page id: {}", pageId);
        client = new WebSocketClient(webSocketUrl, this);
    }

    public int waitSync() {
        return command.waitSync();
    }

    public DevToolsMessage method(String method) {
        return new DevToolsMessage(this, method);
    }

    public DevToolsMessage sendAndWait(String text) {
        Map<String, Object> map = JsonUtils.toJsonDoc(text).read("$");
        DevToolsMessage dtm = new DevToolsMessage(this, map);
        if (dtm.getId() == null) {
            dtm.setId(getNextId());
        }
        return sendAndWait(dtm, null);
    }

    public DevToolsMessage sendAndWait(DevToolsMessage dtm, Predicate<DevToolsMessage> condition) {
        String json = JsonUtils.toJson(dtm.toMap());
        client.send(json);
        logger.debug(">> sent: {}", dtm);
        return waitState.sendAndWait(dtm, condition);
    }

    public void receive(DevToolsMessage dtm) {
        waitState.receive(dtm);
    }

    @Override
    public void onMessage(String text) {
        logger.debug("received raw: {}", text);
        Map<String, Object> map = JsonUtils.toJsonDoc(text).read("$");
        DevToolsMessage dtm = new DevToolsMessage(this, map);
        receive(dtm);
    }

    @Override
    public void onMessage(byte[] bytes) {
        logger.warn("ignoring binary message");
    }

    //==========================================================================
    protected int getWaitInterval() {
        return 0;
    }

    protected DevToolsMessage eval(String expression, Predicate<DevToolsMessage> condition) {
        int count = 0;
        DevToolsMessage dtm;
        do {
            logger.debug("eval try #{}", count + 1);
            dtm = method("Runtime.evaluate").param("expression", expression).send(condition);
            condition = null; // retries don't care about user-condition, e.g. page on-load
        } while (dtm != null && dtm.isResultError() && count++ < 3);
        return dtm;
    }

    @Override
    public void activate() {
        method("Target.activateTarget").param("targetId", pageId).send();
    }

    private int getWindowId() {
        if (_windowId == null) {
            DevToolsMessage dtm = method("Browser.getWindowForTarget").param("targetId", pageId).send();
            _windowId = dtm.getResult("windowId").getValue(Integer.class);
        }
        return _windowId;
    }

    private String getWindowState() {
        return (String) getDimensions().get("windowState");
    }

    @Override
    public Map<String, Object> getDimensions() {
        DevToolsMessage dtm = method("Browser.getWindowForTarget").param("targetId", pageId).send();
        return dtm.getResult("bounds").getAsMap();
    }

    @Override
    public void setDimensions(Map<String, Object> map) {
        Map temp = getDimensions();
        temp.putAll(map);
        temp.remove("windowState");
        method("Browser.setWindowBounds")
                .param("windowId", getWindowId())
                .param("bounds", temp).send();
    }

    @Override
    public void close() {
        method("Page.close").send();
        open = false;
    }

    @Override
    public void quit() {
        if (open) {
            close();
        }
        method("Browser.close").send(WaitState.CHROME_INSPECTOR_DETACHED);
        if (command != null) {
            command.close();
        }
    }

    @Override
    public void setLocation(String url) {
        DevToolsMessage dtm = method("Page.navigate").param("url", url).send(WaitState.CHROME_FRAME_NAVIGATED);
        currentUrl = dtm.getFrameUrl();
    }

    @Override
    public void refresh() {
        method("Page.reload").send();
    }

    @Override
    public void reload() {
        method("Page.reload").param("ignoreCache", true).send();
    }

    private void history(int delta) {
        DevToolsMessage dtm = method("Page.getNavigationHistory").send();
        int currentIndex = dtm.getResult("currentIndex").getValue(Integer.class);
        List<Map> list = dtm.getResult("entries").getValue(List.class);
        int targetIndex = currentIndex + delta;
        if (targetIndex < 0 || targetIndex == list.size()) {
            return;
        }
        Map<String, Object> entry = list.get(targetIndex);
        Integer id = (Integer) entry.get("id");
        String url = (String) entry.get("url");
        method("Page.navigateToHistoryEntry").param("entryId", id).send();
        currentUrl = url;
    }

    @Override
    public void back() {
        history(-1);
    }

    @Override
    public void forward() {
        history(1);
    }

    private void setWindowState(String state) {
        String currentState = getWindowState();
        if (!"normal".equals(currentState)) {
            Predicate<DevToolsMessage> condition = "minimized".equals(currentState) ? null : WaitState.CHROME_FRAME_RESIZED;
            method("Browser.setWindowBounds")
                    .param("windowId", getWindowId())
                    .param("bounds", Collections.singletonMap("windowState", "normal"))
                    .send(condition);
            currentState = "normal";
        }
        if (!currentState.equals(state)) {
            Predicate<DevToolsMessage> condition = "minimized".equals(state) ? null : WaitState.CHROME_FRAME_RESIZED;
            method("Browser.setWindowBounds")
                    .param("windowId", getWindowId())
                    .param("bounds", Collections.singletonMap("windowState", state))
                    .send(condition);
        }
    }

    @Override
    public void maximize() {
        setWindowState("maximized");
    }

    @Override
    public void minimize() {
        setWindowState("minimized");
    }

    @Override
    public void fullscreen() {
        setWindowState("fullscreen");
    }

    @Override
    public void click(String id) {
        eval(DriverUtils.selectorScript(id) + ".click()", null);
    }

    @Override
    public void submit(String id) {
        DevToolsMessage dtm = eval(DriverUtils.selectorScript(id) + ".click()", WaitState.CHROME_FRAME_NAVIGATED);
        currentUrl = dtm.getFrameUrl();
    }

    @Override
    public void focus(String id) {
        eval(DriverUtils.selectorScript(id) + ".focus()", null);
    }

    @Override
    public void input(String id, String value) {
        focus(id);
        for (char c : value.toCharArray()) {
            method("Input.dispatchKeyEvent").param("type", "keyDown").param("text", c + "").send();
        }
    }

    @Override
    public String text(String id) {
        DevToolsMessage dtm = eval(DriverUtils.selectorScript(id) + ".textContent", null);
        return dtm.getResult("value").getAsString();
    }

    @Override
    public String html(String id) {
        DevToolsMessage dtm = eval(DriverUtils.selectorScript(id) + ".innerHTML", null);
        return dtm.getResult("value").getAsString();
    }

    @Override
    public String value(String id) {
        DevToolsMessage dtm = eval(DriverUtils.selectorScript(id) + ".value", null);
        return dtm.getResult("value").getAsString();
    }

    @Override
    public void waitForEvalTrue(String expression) {
        int count = 0;
        ScriptValue sv;
        do {
            DriverUtils.sleep(getWaitInterval());
            logger.debug("poll try #{}", count + 1);
            DevToolsMessage dtm = eval(expression, null);
            sv = dtm.getResult("value");
        } while (!sv.isBooleanTrue() && count++ < 3);
    }

    @Override
    public String getTitle() {
        DevToolsMessage dtm = eval("document.title", null);
        return dtm.getResult("value").getAsString();
    }

    @Override
    public String getLocation() {
        return currentUrl;
    }

    public void enableNetworkEvents() {
        method("Network.enable").send();
    }

    public void enablePageEvents() {
        method("Page.enable").send();
    }

}
