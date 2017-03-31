package redis.clients.jedis;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;

import redis.clients.jedis.exceptions.JedisDataException;

// jfq, 实现pipeline的关键：
//  1. RedisOutputStream有一个buffer，默认为8K。
//  2. Pipeline类向外发送命令的时候，会先把命令发送到这个buffer。
//  3. 如果这个buffer没满，就不会向redis发送命令。最后调用sync的时候，命令才会flush到redis。
//  4. 当然，如果buffer在pipeline使用过程中满了，在调用sync之前就会flush命令到redis。
//     但是，这也是合理的。累积的命令太长，也不符合pipeline的设计初衷。

// jfq, Pipeline类自身主要处理multi/exec/discard三个命令。
// jfq, 因为multi和exec之间的命令，产生的response是不太一样的，因此要特殊处理。
// jfq, 同一个Pipeline，允许多组multi-exec连续执行，但不支持multi内嵌multi。
public class Pipeline extends MultiKeyPipelineBase implements Closeable {

  private MultiResponseBuilder currentMulti;

  private class MultiResponseBuilder extends Builder<List<Object>> {
    private List<Response<?>> responses = new ArrayList<Response<?>>();

    // jfq, 调用这个函数，构造exec的Response对象
    @Override
    public List<Object> build(Object data) {
      @SuppressWarnings("unchecked")
      List<Object> list = (List<Object>) data;
      List<Object> values = new ArrayList<Object>();

      if (list.size() != responses.size()) {
        throw new JedisDataException("Expected data size " + responses.size() + " but was "
            + list.size());
      }

      // jfq, 现在以前累积的Response对象有用了。用来进一步解析Exec产生的对象。
      for (int i = 0; i < list.size(); i++) {
        Response<?> response = responses.get(i);
        response.set(list.get(i));
        Object builtResponse;
        try {
          builtResponse = response.get();
        } catch (JedisDataException e) {
          builtResponse = e;
        }
        values.add(builtResponse);
      }
      return values;
    }

    // jfq, 这个dependency，就是exec命令产生的Response
    public void setResponseDependency(Response<?> dependency) {
      for (Response<?> response : responses) {
        response.setDependency(dependency);
      }
    }

    public void addResponse(Response<?> response) {
      responses.add(response);
    }
  }

  @Override
  protected <T> Response<T> getResponse(Builder<T> builder) {
    if (currentMulti != null) {
      // jfq，当前正在执行multi命令，首先要把立马会产生的queued应答干掉
      super.getResponse(BuilderFactory.STRING); // Expected QUEUED

      // jfq, 然后，把Response对象累积到currentMulti。
      // jfq, 累积起来的Response，在解析Exec命令的时候会用到
      Response<T> lr = new Response<T>(builder);
      currentMulti.addResponse(lr);
      return lr;
    } else {
      return super.getResponse(builder);
    }
  }

  public void setClient(Client client) {
    this.client = client;
  }

  @Override
  protected Client getClient(byte[] key) {
    return client;
  }

  @Override
  protected Client getClient(String key) {
    return client;
  }

  public void clear() {
    if (isInMulti()) {
      discard();
    }

    sync();
  }

  public boolean isInMulti() {
    return currentMulti != null;
  }

  /**
   * Synchronize pipeline by reading all responses. This operation close the pipeline. In order to
   * get return values from pipelined commands, capture the different Response&lt;?&gt; of the
   * commands you execute.
   */
  public void sync() {
    if (getPipelinedResponseLength() > 0) {
      List<Object> unformatted = client.getMany(getPipelinedResponseLength());
      for (Object o : unformatted) {
        generateResponse(o);
      }
    }
  }

  /**
   * Synchronize pipeline by reading all responses. This operation close the pipeline. Whenever
   * possible try to avoid using this version and use Pipeline.sync() as it won't go through all the
   * responses and generate the right response type (usually it is a waste of time).
   * @return A list of all the responses in the order you executed them.
   */
  public List<Object> syncAndReturnAll() {
    if (getPipelinedResponseLength() > 0) {
      List<Object> unformatted = client.getMany(getPipelinedResponseLength());
      List<Object> formatted = new ArrayList<Object>();
      for (Object o : unformatted) {
        try {
          formatted.add(generateResponse(o).get());
        } catch (JedisDataException e) {
          formatted.add(e);
        }
      }
      return formatted;
    } else {
      return java.util.Collections.<Object> emptyList();
    }
  }

  public Response<String> discard() {
    if (currentMulti == null) throw new JedisDataException("DISCARD without MULTI");
    client.discard();
    currentMulti = null;
    return getResponse(BuilderFactory.STRING);
  }

  public Response<List<Object>> exec() {
    if (currentMulti == null) throw new JedisDataException("EXEC without MULTI");

    client.exec();

    // jfq, 注意了，exec命令的Response采用的builder是currentMulti，
    // jfq, 同时，builder内部的Response又依赖于exec的Response。
    Response<List<Object>> response = super.getResponse(currentMulti);
    currentMulti.setResponseDependency(response);
    currentMulti = null;
    return response;
  }

  public Response<String> multi() {
    if (currentMulti != null) throw new JedisDataException("MULTI calls can not be nested");

    client.multi();
    // jfq, 下面的这个response，就是multi命令的response，比如+OK
    Response<String> response = getResponse(BuilderFactory.STRING); // Expecting
    // jfq, 执行完multi命令后，所有后续命令（除了exec/discard)，调用getResponse的时候，都会用到currentMulti
    currentMulti = new MultiResponseBuilder();
    return response;
  }

  @Override
  public void close() {
    clear();
  }

}
