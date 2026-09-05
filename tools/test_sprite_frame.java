// 临时测试：检查 TextureAtlasSprite 的 frame 信息
// 需要在游戏内运行此代码来查看 API

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.SpriteContents;

public class TestSpriteFrame {
    public static void checkSpriteFrame(TextureAtlasSprite sprite) {
        SpriteContents contents = sprite.contents();
        
        // 可能的 API:
        // 1. contents.getFrameIndex() - 返回当前帧索引
        // 2. contents.ticker() - 返回 FrameTicker
        // 3. contents.frameDuration() - 返回当前帧持续帧数
        
        System.out.println("Sprite: " + sprite.contents().name());
        System.out.println("Contents Class: " + contents.getClass().getName());
        
        // 列出所有方法
        for (java.lang.reflect.Method m : contents.getClass().getMethods()) {
            if (m.getName().toLowerCase().contains("frame")) {
                System.out.println("  " + m.getName() + " -> " + m.getReturnType().getSimpleName());
            }
        }
    }
}
