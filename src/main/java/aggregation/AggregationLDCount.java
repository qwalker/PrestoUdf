package aggregation;

import com.facebook.presto.operator.aggregation.state.SliceState;
import com.facebook.presto.spi.block.BlockBuilder;
import com.facebook.presto.spi.block.BlockBuilderStatus;
import com.facebook.presto.spi.type.BigintType;
import com.facebook.presto.spi.type.StandardTypes;
import com.facebook.presto.spi.function.*;

import io.airlift.slice.Slice;
import io.airlift.slice.Slices;

import java.util.*;

/*
计算漏斗的聚合函数, 步骤一

查询12月1号到20号20天, 时间窗口为3天的漏斗:
select ld_sum(temp, 3) from(
select ld_count(xwhen, 20, 3*86400, 1480521600, xwhat, 'Action,loggedin,payment') as temp from tablename
where ds >= '2016-12-01'
and (
    (xwhat = 'Action' and ds <= '2016-12-20') or
    (xwhat in ('loggedin', 'payment') and ds <= '2016-12-22')
) group by xwho);
 */
@AggregationFunction("ld_count")
public class AggregationLDCount extends AggregationBase {

    private static final int COUNT_ONE_LENGTH = 5;          // input中每个事件所占位数, 包含一个int(时间戳)和一个byte(下标)
    private static final int COUNT_FLAG_LENGTH = 5 * 4;     // 状态slice最前边的5位存放临时变量, 每个临时变量都为int类型

    @InputFunction
    public static void input(SliceState state,                                  // 每个用户的状态
                             @SqlType(StandardTypes.BIGINT) long xwhen,         // 当前事件的时间戳
                             @SqlType(StandardTypes.INTEGER) long day_length,   // 当前查询的总天数(1-60)
                             @SqlType(StandardTypes.INTEGER) long win_length,   // 当前查询的时间窗口大小(1-7) * 86400
                             @SqlType(StandardTypes.INTEGER) long start_day,    // 当前查询的起始日期的时间戳
                             @SqlType(StandardTypes.VARCHAR) Slice xwhat,       // 当前事件的名称, A还是B或者C
                             @SqlType(StandardTypes.VARCHAR) Slice events) {    // 当前查询的全部事件, 逗号分隔
        // 获取状态
        Slice slice = state.getSlice();

        // 判断是否需要初始化events
        if (!event_pos_dict.containsKey(events)) {
            init_events(events);
        }

        // 初始化某一个用户的state
        if (null == slice) {
            // 初始化slice, 第一次初始化100个COUNT_ONE_LENGTH
            slice = Slices.allocate(COUNT_FLAG_LENGTH + 100 * COUNT_ONE_LENGTH);

            // 存放前5位int类型临时变量
            slice.setInt(0, 100);                   // 第1个int存放剩余个数, 每次-1
            slice.setInt(4, COUNT_FLAG_LENGTH);     // 第2个int存放当前下标, 每次+5
            slice.setInt(8, (int) day_length);      // 第3个int存放day_length总天数
            slice.setInt(12, (int) win_length);     // 第4个int存放win_length窗口大小
            slice.setInt(16, (int) start_day);      // 第5个int存放start_day(时间戳)
        }

        // 获取中间变量
        int retained = slice.getInt(0);
        int index = slice.getInt(4);

        // 判断是否需要更新
        if (retained == 0) {
            // 每次增加50个COUNT_ONE_LENGTH
            Slice slice_new = Slices.allocate(slice.length() + 50 * COUNT_ONE_LENGTH);
            slice_new.setBytes(0, slice.getBytes());

            // 更新变量
            slice = slice_new;
            retained += 50;
        }

        // 更新变量--每个事件的时间戳和下标
        slice.setInt(index, (int) xwhen);
        slice.setByte(index + 4, event_pos_dict.get(events).get(xwhat));

        // 更新变量--每个用户的状态
        slice.setInt(0, retained - 1);
        slice.setInt(4, index + COUNT_ONE_LENGTH);

        // 返回结果
        state.setSlice(slice);
    }

    @CombineFunction
    public static void combine(SliceState state, SliceState otherState) {
        // 获取状态
        Slice slice = state.getSlice();
        Slice otherslice = otherState.getSlice();

        // 更新状态, 并返回结果
        if (null == slice) {
            state.setSlice(Slices.copyOf(otherslice, 0, otherslice.getInt(4)));
        } else {
            // 获取变量
            int length1 = slice.getInt(4) - COUNT_FLAG_LENGTH;
            int length2 = otherslice.getInt(4) - COUNT_FLAG_LENGTH;

            // 初始化
            Slice slice_new = Slices.allocate(COUNT_FLAG_LENGTH + length1 + length2);

            // 赋值
            slice_new.setBytes(0, slice.getBytes(), 0, COUNT_FLAG_LENGTH + length1);
            slice_new.setBytes(COUNT_FLAG_LENGTH + length1, otherslice.getBytes(), COUNT_FLAG_LENGTH, length2);

            // 更改变量
            slice_new.setInt(4, slice_new.length());

            // 返回结果
            state.setSlice(slice_new);
        }
    }

    @OutputFunction("array(" + StandardTypes.BIGINT + ")")
    public static void output(SliceState state, BlockBuilder out) {
        // 获取状态
        Slice slice = state.getSlice();
        if (null == slice) {
            // 数据为空，返回一个空数组
            BlockBuilder blockBuilder = BigintType.BIGINT.createBlockBuilder(new BlockBuilderStatus(), 0);
            out.writeObject(blockBuilder.build());
            out.closeEntry();
            return;
        }

        // 获取中间变量
        int day_length = slice.getInt(8);
        int win_length = slice.getInt(12);
        int start_day = slice.getInt(16);

        // 构造列表和字典
        List<Integer> time_array = new ArrayList<>();
        Map<Integer, Byte> time_xwhat_map = new HashMap<>();
        for (int i = COUNT_FLAG_LENGTH; i < slice.length(); i += COUNT_ONE_LENGTH) {
            int timestamp = slice.getInt(i);

            // 如果不走combine过程，时间戳可能为0
            if (timestamp <= 0) break;

            // 赋值time_array和time_xwhat_map
            time_array.add(timestamp);
            time_xwhat_map.put(timestamp, slice.getByte(i + 4));
        }

        // 排序时间戳数组
        Collections.sort(time_array);

        // 遍历时间戳数据
        List<int[]> temp = new ArrayList<>();
        for (int timestamp: time_array) {
            // 事件有序进入
            byte xwhat = time_xwhat_map.get(timestamp);
            if (xwhat == 0) {
                // 新建临时对象, 存放每一个事件的时间戳, 最后一位存放事件步骤数
                int[] flag = new int[MAX_COUNT_BYTE + 1];
                // 临时对象赋值
                flag[0] = timestamp;
                flag[MAX_COUNT_BYTE] = 1;
                temp.add(flag);
            } else {
                // 更新临时对象: 从后往前, 并根据条件适当跳出
                for (int i = temp.size() - 1; i >= 0; --i) {
                    int[] flag = temp.get(i);
                    if ((timestamp - flag[0]) >= win_length) {
                        // 当前事件的时间减去flag[0]不合法, 跳出
                        break;
                    } else {
                        // 当前事件的时间减去flag[0]合法
                        if (flag[xwhat - 1] > 0 && flag[xwhat] == 0) {
                            // 当前事件的上一个事件存在, 并且不存在当前事件, 更新数据，并跳出
                            flag[xwhat] = timestamp;
                            flag[MAX_COUNT_BYTE] = xwhat + 1;
                            break;
                        }
                    }
                }
            }
        }

        // 构造结果, 存放每一天的最大步骤数, 最后一位为总步骤数
        int[] result = new int[day_length + 1];
        for (int[] flag: temp) {
            // 计算是具体哪一天
            int index = (flag[0] - start_day) / 86400;

            // 时间戳和ds分布不一致导致该问题
            if (index < 0 || index >= day_length) {
                continue;
            }

            // 更新变量
            if (result[index] < flag[MAX_COUNT_BYTE]) {
                result[index] = flag[MAX_COUNT_BYTE];
                if (result[day_length] < flag[MAX_COUNT_BYTE]) {
                    result[day_length] = flag[MAX_COUNT_BYTE];
                }
            }
        }

        // 返回结果, 返回每一天的最大步骤数, 最后一位为总步骤数
        BlockBuilder blockBuilder = BigintType.BIGINT.createBlockBuilder(new BlockBuilderStatus(), day_length + 1);
        for(int flag : result) {
            BigintType.BIGINT.writeLong(blockBuilder, flag);
        }
        out.writeObject(blockBuilder.build());
        out.closeEntry();
    }
}