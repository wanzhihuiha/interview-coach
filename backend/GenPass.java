import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 供开发人员在本地命令行生成 BCrypt 哈希字符串的独立辅助程序。
 *
 * <p>该类型不由 Spring 创建，也不参与 Interview Coach 应用启动、用户注册或账号初始化；
 * 生成结果只写到当前进程的标准输出。</p>
 */
public class GenPass {

    /**
     * 使用第一个命令行参数生成 BCrypt 哈希；未提供参数时由数组访问异常直接终止进程。
     *
     * @param args {@code args[0]} 为待编码的本地输入文本
     */
    public static void main(String[] args) {
        // 每次运行创建编码器并对本次输入计算带随机盐的 BCrypt 结果。
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        // 仅把生成结果交给命令行调用者，不写入应用配置或用户数据。
        System.out.println(encoder.encode(args[0]));
    }
}
