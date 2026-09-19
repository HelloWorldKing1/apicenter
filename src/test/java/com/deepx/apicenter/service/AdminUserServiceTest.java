package com.deepx.apicenter.service;

import com.deepx.apicenter.exception.BizException;
import com.deepx.apicenter.model.AdminUserRow;
import com.deepx.apicenter.repository.AdminSessionRepository;
import com.deepx.apicenter.repository.AdminUserRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 账号管理守卫单测（2026-09-18，Mockito，不连库）：
 * 「不把系统锁死」的两条底线必须在**确定性**环境里钉死 —— 集成测试跑在共享开发库上，
 * 库里是否有别的可用账号取决于环境，无法稳定复现「最后一个可用账号」。
 *
 * <p>守卫判定顺序：① 最后一个可用账号 ② 自己（顺序固定，单账号环境下提示更贴切）。
 */
class AdminUserServiceTest {

    private final AdminUserRepository userRepository = mock(AdminUserRepository.class);
    private final AdminSessionRepository sessionRepository = mock(AdminSessionRepository.class);
    private final PasswordHasher hasher = new PasswordHasher();
    private final AdminUserService service = new AdminUserService(userRepository, sessionRepository, hasher);

    private static final long ME = 1L;
    private static final long OTHER = 2L;

    private AdminUserRow user(long id, String username, String status) {
        return new AdminUserRow(id, username, "名字", "pbkdf2$1$x$y", status, 0, null, null, null);
    }

    // ---------- 守卫 ①：最后一个可用账号 ----------

    @Test
    void 只有一个可用账号_停用被拒_提示最后账号且不写库() {
        when(userRepository.findById(OTHER)).thenReturn(Optional.of(user(OTHER, "other", "ENABLED")));
        when(userRepository.countEnabled()).thenReturn(1);

        assertThatThrownBy(() -> service.update(ME, OTHER, null, "DISABLED"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("最后一个可用账号")
                .hasMessageContaining("停用");

        verify(userRepository, never()).updateProfile(anyLong(), any(), any());
    }

    @Test
    void 只有一个可用账号_删除被拒_提示最后账号且不删库() {
        when(userRepository.findById(OTHER)).thenReturn(Optional.of(user(OTHER, "other", "ENABLED")));
        when(userRepository.countEnabled()).thenReturn(1);

        assertThatThrownBy(() -> service.delete(ME, OTHER))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("最后一个可用账号");

        verify(userRepository, never()).delete(anyLong());
    }

    @Test
    void 已停用账号不受最后账号守卫限制_可直接删除() {
        when(userRepository.findById(OTHER)).thenReturn(Optional.of(user(OTHER, "other", "DISABLED")));

        service.delete(ME, OTHER);

        verify(userRepository).delete(OTHER);
        verify(sessionRepository).deleteByUserId(OTHER);
    }

    // ---------- 守卫 ②：不能对自己操作 ----------

    @Test
    void 有多个可用账号_停用自己被拒_提示不能停用当前账号() {
        when(userRepository.findById(ME)).thenReturn(Optional.of(user(ME, "me", "ENABLED")));
        when(userRepository.countEnabled()).thenReturn(2);

        assertThatThrownBy(() -> service.update(ME, ME, null, "DISABLED"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能停用当前登录的账号");
    }

    @Test
    void 删除自己被拒() {
        when(userRepository.findById(ME)).thenReturn(Optional.of(user(ME, "me", "ENABLED")));
        when(userRepository.countEnabled()).thenReturn(5);

        assertThatThrownBy(() -> service.delete(ME, ME))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能删除当前登录的账号");
    }

    @Test
    void 重置自己被拒_引导走修改密码() {
        when(userRepository.findById(ME)).thenReturn(Optional.of(user(ME, "me", "ENABLED")));

        assertThatThrownBy(() -> service.resetPassword(ME, ME, "NewPassw0rd"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("修改密码");

        verify(userRepository, never()).updatePassword(anyLong(), any());
    }

    // ---------- 正常路径 ----------

    @Test
    void 停用他人_改状态并吊销其全部会话() {
        when(userRepository.findById(OTHER)).thenReturn(Optional.of(user(OTHER, "other", "ENABLED")));
        when(userRepository.countEnabled()).thenReturn(3);

        service.update(ME, OTHER, "新显示名", "disabled");   // 小写状态也应接受

        verify(userRepository).updateProfile(OTHER, "新显示名", "DISABLED");
        verify(sessionRepository).deleteByUserId(OTHER);
    }

    @Test
    void 仅改显示名不动状态时不吊销会话() {
        when(userRepository.findById(OTHER)).thenReturn(Optional.of(user(OTHER, "other", "ENABLED")));

        service.update(ME, OTHER, "只改名", null);

        verify(userRepository).updateProfile(OTHER, "只改名", "ENABLED");
        verify(sessionRepository, never()).deleteByUserId(anyLong());
    }

    @Test
    void 重置他人口令_写摘要并吊销会话() {
        when(userRepository.findById(OTHER)).thenReturn(Optional.of(user(OTHER, "other", "ENABLED")));

        service.resetPassword(ME, OTHER, "NewPassw0rd");

        verify(userRepository).updatePassword(eq(OTHER), any());
        verify(sessionRepository).deleteByUserId(OTHER);
    }

    @Test
    void 新建账号_重名40901_非法用户名与弱口令40001() {
        when(userRepository.findByUsername("dup")).thenReturn(Optional.of(user(OTHER, "dup", "ENABLED")));

        assertThatThrownBy(() -> service.create(ME, "dup", "Passw0rd", null))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(BizException.USERNAME_TAKEN));
        assertThatThrownBy(() -> service.create(ME, "ab", "Passw0rd", null))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(BizException.FIELD_INVALID));
        assertThatThrownBy(() -> service.create(ME, "ok_name", "abcdefgh", null))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(BizException.FIELD_INVALID));
    }

    @Test
    void 不存在的账号_40405() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(ME, 999L, null, "DISABLED"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(BizException.ADMIN_USER_NOT_FOUND));
    }

    @Test
    void 非法状态值被拒() {
        when(userRepository.findById(OTHER)).thenReturn(Optional.of(user(OTHER, "other", "ENABLED")));

        assertThatThrownBy(() -> service.update(ME, OTHER, null, "CANCELLED"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("ENABLED 或 DISABLED");
    }
}
