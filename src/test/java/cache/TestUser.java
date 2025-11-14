package cache;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TestUser {
    private Long id;
    private String name;
    private String email;
    private Long expireTime; // 用于测试动态过期时间
}



