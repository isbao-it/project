/* 2019-05-06
*table tbody合并单元格
*/
function merges(data, indexs, fields,tableid) {
    for (var i = 0; i < indexs.length; i++) {
        merge(data, indexs[i], fields[i],tableid);
    }
    var $trs = $("div[lay-id='"+tableid+"']>.layui-table-box>.layui-table-fixed-l>.layui-table-body>.layui-table tr");
    $trs.find('[del="true"]').remove();
    console.log($trs);
}

function merge(data, index, field,tableid) {
    var $trs = $("div[lay-id='"+tableid+"']>.layui-table-box>.layui-table-fixed-l>.layui-table-body>.layui-table tr");
    console.log($trs);
    var lastValue = data[0][field], spanNum = 1;
    for (var i = 1; i < data.length; i++) {
        if (data[i][field] == lastValue) {
            spanNum++;
            if (i == data.length - 1) {
                $trs.eq(i - spanNum + 1).find('td').eq(index).attr('rowspan', spanNum);
                for (var j = 1; j < spanNum; j++) {
                    $trs.eq(i - j + 1).find('td').eq(index).attr('del', 'true');
                }
            }
        } else {
            $trs.eq(i - spanNum).find('td').eq(index).attr('rowspan', spanNum);
            for (var j = 1; j < spanNum; j++) {
                $trs.eq(i - j).find('td').eq(index).attr('del', 'true');
            }
            spanNum = 1;
            lastValue = data[i][field];
        }
    }
}

