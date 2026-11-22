package com.seekstars.muselauncher;

import java.util.ArrayList;
import java.util.List;

public class Account {
    public final long id;
    public String name;
    /** 文字头像：1-2 个字符或 emoji，默认 "m"；设置图片头像后作为图片清除时的回退 */
    public String avatar = "m";

    /** 图片头像：true 时使用 AvatarStore 保存的 acct_<id>.png（照片/SVG 转换而来） */
    public boolean hasImage;

    /** 桌面快捷方式令牌：创建 pinned shortcut 时生成，启动时校验（防第三方 intent 冒开账号） */
    public String shortcutToken;
    /** 代理地址，空 = 跟随系统网络 */
    public String proxy = "";
    /** 标签（侧栏可按标签搜索） */
    public List<String> tags = new ArrayList<>();
    /** 登录态：运行时从 CookieManager 探测，不持久化 */
    public boolean logged;

    public Account(long id, String name) {
        this.id = id;
        this.name = name;
    }
}
