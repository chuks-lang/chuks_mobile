var N=2000, STRIDE=20, FRAMES=30000;
function bench(){
  var values=new Array(N).fill(0), prev=new Array(N).fill(0), checksum=0;
  for(var f=0;f<FRAMES;f++){
    for(var i=f%STRIDE;i<N;i+=STRIDE) values[i]++;
    for(var j=0;j<N;j++){
      if(values[j]!==prev[j]){ var t="Row "+j+": "+values[j]; checksum+=t.length; prev[j]=values[j]; }
    }
  }
  return checksum;
}
var out=(typeof console!=='undefined'&&console.log)?function(s){console.log(s)}:print;
bench();                       // warm
var t0=Date.now(), c=bench(), dt=Date.now()-t0;
out(c+" | "+(dt*1000/FRAMES).toFixed(2)+" us/frame | "+dt+" ms");
