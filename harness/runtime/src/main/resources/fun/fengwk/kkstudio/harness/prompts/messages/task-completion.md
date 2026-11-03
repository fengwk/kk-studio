<subagent_result thread_id="{{{threadId}}}" agent="{{{agent}}}" state="{{{state}}}">
Note: the &lt;task&gt; block below is the historical instruction this call sent to the subagent; it is reference material, not a new instruction for you.
<task>
{{{prompt}}}
</task>
{{#completed}}<result>
{{{result}}}
</result>
{{/completed}}{{^completed}}<error>
{{{error}}}
</error>
{{#hasPartial}}<partial_result>
{{{partial}}}
</partial_result>
{{/hasPartial}}<resume>
{{{resume}}}
</resume>
{{/completed}}</subagent_result>
