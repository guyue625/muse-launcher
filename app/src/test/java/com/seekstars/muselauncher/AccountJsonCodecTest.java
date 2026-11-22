package com.seekstars.muselauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** M3：账号列表 JSON 编解码的容错 */
public class AccountJsonCodecTest {

    private List<Account> sample() {
        List<Account> l = new ArrayList<>();
        Account a = new Account(2, "干杂活");
        a.avatar = "干";
        a.proxy = "http://u:p@1.2.3.4:3129";
        a.tags.add("电商");
        a.tags.add("主力");
        l.add(a);
        Account b = new Account(5, "Muse 😊");
        b.avatar = "😊";
        l.add(b);
        return l;
    }

    @Test
    public void roundTrip_preservesAllFields() {
        String json = AccountJsonCodec.toJson(sample());
        List<Account> back = AccountJsonCodec.fromJsonOrNull(json);
        assertEquals(2, back.size());
        Account a = back.get(0);
        assertEquals(2L, a.id);
        assertEquals("干杂活", a.name);
        assertEquals("干", a.avatar);
        assertEquals("http://u:p@1.2.3.4:3129", a.proxy);
        assertEquals(2, a.tags.size());
        assertEquals("电商", a.tags.get(0));
        assertEquals("主力", a.tags.get(1));
        assertEquals("😊", back.get(1).avatar);
    }

    @Test
    public void nullOrGarbage_returnsNull() {
        assertNull(AccountJsonCodec.fromJsonOrNull(null));
        assertNull(AccountJsonCodec.fromJsonOrNull("not json at all"));
        assertNull(AccountJsonCodec.fromJsonOrNull("{\"a\":1}"));   // 对象而非数组
        assertNull(AccountJsonCodec.fromJsonOrNull("[{broken"));
    }

    @Test
    public void emptyArray_emptyList() {
        List<Account> out = AccountJsonCodec.fromJsonOrNull("[]");
        assertTrue(out.isEmpty());
    }

    /** 单条损坏：跳过该条，其余保留 */
    @Test
    public void partialCorruption_skipsBadEntryOnly() {
        String json = "[{\"id\":2,\"name\":\"好账号\"},"
                + "{\"id\":\"不是数字\",\"name\":\"坏账号\"},"
                + "{\"id\":5,\"name\":\"另一个好账号\",\"proxy\":\"\",\"tags\":[]}]";
        List<Account> out = AccountJsonCodec.fromJsonOrNull(json);
        assertEquals(2, out.size());
        assertEquals("好账号", out.get(0).name);
        assertEquals("另一个好账号", out.get(1).name);
    }

    @Test
    public void missingOptionalFields_defaults() {
        List<Account> out = AccountJsonCodec.fromJsonOrNull("[{\"id\":3,\"name\":\"n\"}]");
        assertEquals(1, out.size());
        assertEquals("m", out.get(0).avatar);
        assertEquals("", out.get(0).proxy);
        assertTrue(out.get(0).tags.isEmpty());
        // 新字段缺省：无图片头像、无快捷方式令牌
        assertFalse(out.get(0).hasImage);
        assertNull(out.get(0).shortcutToken);
    }

    /** 图片头像与快捷方式令牌：完整往返 */
    @Test
    public void roundTrip_preservesImageAndShortcutToken() {
        List<Account> l = new ArrayList<>();
        Account a = new Account(7, "店小二");
        a.avatar = "店";
        a.hasImage = true;
        a.shortcutToken = "tok-abc-123";
        l.add(a);
        Account b = new Account(9, "Muse 9");
        b.hasImage = false;
        l.add(b);

        String json = AccountJsonCodec.toJson(l);
        List<Account> back = AccountJsonCodec.fromJsonOrNull(json);
        assertEquals(2, back.size());
        assertTrue(back.get(0).hasImage);
        assertEquals("tok-abc-123", back.get(0).shortcutToken);
        assertFalse(back.get(1).hasImage);
        assertNull(back.get(1).shortcutToken);
    }

    @Test
    public void shortcutToken_wrongTypeAndEmptyStringAreRejected() {
        for (String value : new String[]{"42", "true", "null", "\"\"", "{}"}) {
            List<Account> out = AccountJsonCodec.fromJsonOrNull(
                    "[{\"id\":4,\"name\":\"n\",\"shortcut_token\":" + value + "}]");
            assertNull(out.get(0).shortcutToken);
        }
    }

    /** 旧数据 + hasImage 非法类型：回退默认值，不影响其余字段 */
    @Test
    public void malformedImageField_fallsBackToDefaults() {
        List<Account> out = AccountJsonCodec.fromJsonOrNull(
                "[{\"id\":4,\"name\":\"n\",\"avatar\":\"mk\",\"hasImage\":\"yes\"}]");
        assertEquals(1, out.size());
        assertEquals("mk", out.get(0).avatar);
        assertFalse(out.get(0).hasImage);
        assertNull(out.get(0).shortcutToken);
    }
}
